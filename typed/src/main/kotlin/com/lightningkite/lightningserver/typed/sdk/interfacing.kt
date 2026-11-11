package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.plus
import com.lightningkite.lightningserver.typed.sdk.SDK.processToModules
import com.lightningkite.lightningserver.typed.sdk.SDK.sdk
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter

public data class InterfaceInfo(
    val type: KClass<*>,
    val typeParameters: List<KSerializer<*>> = emptyList(),
    val imports: Set<String> = emptySet(),
) {
    init {
        require(type.typeParameters.size == typeParameters.size) {
            """
                InterfaceInfo requires a KSerializer for each type parameter.
                
                Type parameters: ${type.typeParameters}, provided: ${typeParameters.joinToString { "KSerializer(${it.descriptor.serialName})" }}
            """.trimIndent()
        }
    }

    public companion object : MutableExtensions.Key<InterfaceInfo>

    public fun kotlinString(qualified: Boolean = true): String {
        val params = typeParameters
            .takeUnless { it.isEmpty() }
            ?.joinToString(prefix = "<", postfix = ">") { it.kotlinTypeString() }
            ?: ""

        val name = (if (qualified) type.qualifiedName else type.simpleName)
            ?: throw IllegalArgumentException("Cannot find ${if (qualified) "qualified " else ""}name for $type")

        return name + params
    }
}

public fun KClass<*>.info(vararg typeParams: KSerializer<*>): InterfaceInfo = InterfaceInfo(this, typeParams.toList())

/**
 * The client interface this module's endpoints implement in generated SDKs, or `null` to generate its functions.
 *
 * Setting it also makes the build fail if the module doesn't implement the interface: every abstract function
 * needs an endpoint with the same [functionName][com.lightningkite.lightningserver.typed.ApiHttpHandler.functionName],
 * argument types and return type. A non-`suspend` function returning a `ClientWebSocket` needs a websocket with the
 * same input and output types instead. Extra endpoints are allowed.
 */
public var SdkSettings.clientInterface: InterfaceInfo?
    get() = extensions[InterfaceInfo]
    set(value) {
        if (extensions[InterfaceInfo] == null) builder.buildCheck { builder.checkClientInterface(it) }
        extensions[InterfaceInfo] = value
    }

@Suppress("UNCHECKED_CAST")
@OptIn(InternalLightningServerApi::class, ExperimentalSerializationApi::class)
private fun ServerBuilder.checkClientInterface(root: ServerDefinition) {
    fun ServerDefinition.find(path: PathSpec0): Locationed<PathSpec0, ServerDefinition>? =
        if (thisLayer.moduleId == moduleId) Locationed(path, this)
        else modules.firstNotNullOfOrNull { (location, module) -> module.find(path + location) }

    // Claude: checks are only collected from builders in the tree, so this one is always found.
    val (location, definition) = root.find(PathSpec.root)!!
    val module = definition.sdk().processToModules()
    val interfaces = module.extendsInterfaces.map { it.item }
    val typeArguments = interfaces.flatMap { it.type.typeParameters.zip(it.typeParameters) }.toMap()

    // Claude: resolves a type the way handlers resolve theirs (serializerOrContextual), with the interface's type parameters filled in.
    fun KType.serializer(): KSerializer<*> = when (val classifier = classifier) {
        is KTypeParameter -> (typeArguments.getValue(classifier) as KSerializer<Any>).let { if (isMarkedNullable) it.nullable else it }
        else -> {
            val args = arguments.map { it.type!!.serializer() }
            try {
                EmptySerializersModule().serializer(classifier as KClass<*>, args, isMarkedNullable)
            } catch (e: SerializationException) {
                (ContextualSerializer(classifier as KClass<*>, null, args.toTypedArray()) as KSerializer<Any>)
                    .let { if (isMarkedNullable) it.nullable else it }
            }
        }
    }

    // Claude: the argument types, then the return type or a websocket's input and output types.
    fun KFunction<*>.signature(): Pair<List<SerialDescriptor>, List<SerialDescriptor>> =
        parameters.filter { it.kind == KParameter.Kind.VALUE }.map { it.type.serializer().descriptor } to
            if (isSuspend) listOf(returnType.serializer().descriptor)
            else returnType.arguments.map { it.type!!.serializer().descriptor }
    fun SDK.Function.signature(): Pair<List<SerialDescriptor>, List<SerialDescriptor>> =
        arguments.map { it.type.descriptor } to when (this) {
            is SDK.Function.Endpoint -> listOf(outputType.descriptor)
            is SDK.Function.WebSocket -> listOf(inputType.descriptor, outputType.descriptor)
        }
    // Claude: compares serial names, not descriptors, because servers may use their own serializer for a type (ServerFile does).
    fun SerialDescriptor.sameType(other: SerialDescriptor): Boolean =
        serialName == other.serialName && isNullable == other.isNullable &&
            (kind != StructureKind.LIST && kind != StructureKind.MAP ||
                elementDescriptors.zip(other.elementDescriptors).all { (a, b) -> a.sameType(b) })
    fun List<SerialDescriptor>.sameTypes(other: List<SerialDescriptor>) =
        size == other.size && zip(other).all { (a, b) -> a.sameType(b) }
    fun Pair<List<SerialDescriptor>, List<SerialDescriptor>>.text(webSocket: Boolean) =
        first.joinToString(prefix = "(", postfix = "): ") { it.serialName } +
            second.joinToString(prefix = if (webSocket) "ClientWebSocket<" else "", postfix = if (webSocket) ">" else "") { it.serialName }

    val abstractFunctions = interfaces.flatMap { it.type.members }.filterIsInstance<KFunction<*>>().filter { it.isAbstract }.distinct()
    val problems = abstractFunctions.mapNotNull { function ->
        val expected = function.signature()
        val kind = if (function.isSuspend) "endpoint" else "websocket"
        val candidates = module.functionOverrides.filter { it.functionName == function.name && (it is SDK.Function.Endpoint) == function.isSuspend }
        when {
            candidates.any { candidate ->
                val actual = candidate.signature()
                actual.first.sameTypes(expected.first) && actual.second.sameTypes(expected.second)
            } -> null
            candidates.isEmpty() -> "- ${function.name}${expected.text(!function.isSuspend)} has no $kind with that functionName"
            else -> "- ${function.name}${expected.text(!function.isSuspend)} doesn't match the $kind's " +
                candidates.joinToString(" or ") { it.signature().text(!function.isSuspend) }
        }
    }
    check(problems.isEmpty()) {
        "The module at $location doesn't implement its client interface ${interfaces.joinToString { it.type.qualifiedName.toString() }}:\n" +
            problems.joinToString("\n")
    }
}

