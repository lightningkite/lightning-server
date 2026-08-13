package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.data.serialNameFQN
import com.lightningkite.services.database.*
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.internal.GeneratedSerializer

@OptIn(ExperimentalSerializationApi::class)
public fun KSerializer<*>.kotlinTypeString(): String {
    return when (this.descriptor.kind) {
        StructureKind.MAP -> "Map<String, ${this.mapValueElement()!!.kotlinTypeString()}>"

        StructureKind.LIST -> "List<${this.listElement()!!.kotlinTypeString()}>"
        SerialKind.CONTEXTUAL -> descriptor.capturedKClass?.qualifiedName ?: descriptor.serialNameFQN()
        else -> {
            descriptor.serialName.substringBefore('/').substringBefore('<') +
                    (typeParametersSerializersOrNull()
                        ?.takeUnless { it.isEmpty() }
                        ?.joinToString(", ", "<", ">") { it.kotlinTypeString() }
                        ?: "")
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal fun KSerializer<*>.kotlinTypeStringWithReplacements(
    replacements: Map<KSerializer<*>, String>
): String {
    replacements.entries
        .find { it.key deepEquals this@kotlinTypeStringWithReplacements }
        ?.let { return it.value }

    return when (this.descriptor.kind) {
        StructureKind.MAP -> "Map<String, ${this.mapValueElement()!!.kotlinTypeStringWithReplacements(replacements)}>"

        StructureKind.LIST -> "List<${this.listElement()!!.kotlinTypeStringWithReplacements(replacements)}>"
        SerialKind.CONTEXTUAL -> descriptor.capturedKClass?.qualifiedName ?: descriptor.serialNameFQN()
        else -> {
            descriptor.serialName.substringBefore('/').substringBefore('<') +
                    (typeParametersSerializersOrNull()
                        ?.takeUnless { it.isEmpty() }
                        ?.joinToString(", ", "<", ">") { it.kotlinTypeStringWithReplacements(replacements) }
                        ?: "")
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
context(server: ServerRuntime)
public fun KSerializer<*>.kotlinSerializer(): String {
    nullElement()?.let { return it.kotlinSerializer() + ".nullable" }

    return when (this.descriptor.kind) {
        StructureKind.MAP -> "MapSerializer(String.serializer(), ${
            this.mapValueElement()!!.kotlinSerializer()
        })"

        StructureKind.LIST -> "ListSerializer(${this.listElement()!!.kotlinSerializer()})"

        SerialKind.CONTEXTUAL -> "ContextualSerializer(${kotlinTypeString()}::class, null, arrayOf(${
            this.typeParametersSerializersOrNull()?.joinToString(", ") { it.kotlinSerializer() } ?: ""
        }))"

        else ->
            if (descriptor.serialName == "kotlin.Nothing") "kotlinx.serialization.builtins.NothingSerializer()"
            else descriptor.serialName
                .substringBefore('/')
                .substringBefore('<')
                .plus(".serializer")
                .plus(typeParametersSerializersOrNull()?.joinToString(", ", "(", ")") { it.kotlinSerializer() } ?: "()")
    }
}


@OptIn(ExperimentalSerializationApi::class)
context(server: ServerRuntime)
internal fun KSerializer<*>.kotlinSerializerWithReplacements(
    replacements: Map<KSerializer<*>, String>
): String {
    replacements
        .entries
        .find { this@kotlinSerializerWithReplacements deepEquals it.key }
        ?.let { return it.value }

    nullElement()?.let { return it.kotlinSerializerWithReplacements(replacements) + ".nullable" }

    return when (this.descriptor.kind) {
        StructureKind.MAP -> "MapSerializer(String.serializer(), ${
            this.mapValueElement()!!.kotlinSerializerWithReplacements(replacements)
        })"

        StructureKind.LIST -> "ListSerializer(${this.listElement()!!.kotlinSerializerWithReplacements(replacements)})"

        SerialKind.CONTEXTUAL -> "ContextualSerializer(${kotlinTypeString()}::class, null, arrayOf(${
            this.typeParametersSerializersOrNull()?.joinToString(", ") { it.kotlinSerializerWithReplacements(replacements) } ?: ""
        }))"

        else ->
            if (descriptor.serialName == "kotlin.Nothing") "kotlinx.serialization.builtins.NothingSerializer()"
            else descriptor.serialName
                .substringBefore('/')
                .substringBefore('<')
                .plus(".serializer")
                .plus(typeParametersSerializersOrNull()?.joinToString(", ", "(", ")") { it.kotlinSerializerWithReplacements(replacements) } ?: "()")
    }
}

public fun KSerializer<*>.isUnit(): Boolean = descriptor.serialName == "kotlin.Unit"

/** One subtype of a sealed type: its on-the-wire discriminator [name] and its [serializer]. */
public class SealedOptionInfo(public val name: String, public val serializer: KSerializer<*>)

public infix fun KSerializer<*>.deepEquals(other: KSerializer<*>): Boolean {
    if (this.descriptor != other.descriptor) return false

    val myTypes = this.typeParametersSerializersOrNull()
    val otherTypes = other.typeParametersSerializersOrNull()

    if (myTypes == null && otherTypes == null) return true
    if (myTypes?.size != otherTypes?.size) return false

    return myTypes.orEmpty().zip(otherTypes.orEmpty()).all { (a, b) -> a.deepEquals(b) }
}

/**
 * The subtypes of a sealed/polymorphic serializer, or null if this isn't one.
 *
 * Covers the two polymorphic serializers Lightning Server actually emits: framework
 * [MySealedClassSerializerInterface] types (wrapper wire format `{ "<name>": value }`)
 * and app `@Serializable sealed` types, which the registry virtualizes to
 * [VirtualSealed] (flat discriminator wire format `{ "type": "<name>", ...fields }`).
 */
@OptIn(ExperimentalSerializationApi::class)
public fun KSerializer<*>.sealedOptionsOrNull(): List<SealedOptionInfo>? = when {
    this is MySealedClassSerializerInterface<*> -> options.map { SealedOptionInfo(it.baseName, it.serializer) }
    this is VirtualSealed.Concrete -> serializableOptions.map { SealedOptionInfo(it.name, it.serializer) }
    descriptor.kind == PolymorphicKind.SEALED -> serializableOptions?.map { SealedOptionInfo(it.name, it.serializer) }
    else -> null
}

/** True for [MySealedClassSerializerInterface] types, which use the `{ "<name>": value }` wrapper format. */
public fun KSerializer<*>.isWrapperSealed(): Boolean = this is MySealedClassSerializerInterface<*>


@OptIn(InternalSerializationApi::class)
public fun KSerializer<*>.subSerializers(): Array<KSerializer<*>> = nullElement()?.let { arrayOf(it) }
    ?: listElement()?.let { arrayOf(it) }
    ?: mapValueElement()?.let { arrayOf(it) }
    ?: (this as? GeneratedSerializer<*>)?.typeParametersSerializers()
    ?: (this as? ConditionSerializer<*>)?.inner?.let { arrayOf(it) }
    ?: (this as? ModificationSerializer<*>)?.inner?.let { arrayOf(it) }
    ?: (this as? PartialSerializer<*>)?.source?.let { arrayOf(it) }
    ?: (this as? SortPartSerializer<*>)?.inner?.let { arrayOf(it) }
    ?: (this as? DataClassPathSerializer<*>)?.inner?.let { arrayOf(it) }
    ?: arrayOf()

@OptIn(InternalSerializationApi::class)
public fun KSerializer<*>.subAndChildSerializers(): Array<KSerializer<*>> = nullElement()?.let { arrayOf(it) }
    ?: serializableProperties?.map { it.serializer }?.toTypedArray()
    ?: listElement()?.let { arrayOf(it) }
    ?: mapValueElement()?.let { arrayOf(it) }
    ?: (this as? GeneratedSerializer<*>)?.run { childSerializers() + typeParametersSerializers() }
    ?: (this as? ConditionSerializer<*>)?.inner?.let { arrayOf(it) }
    ?: (this as? ModificationSerializer<*>)?.inner?.let { arrayOf(it) }
    ?: (this as? PartialSerializer<*>)?.source?.let { arrayOf(it) }
    ?: (this as? SortPartSerializer<*>)?.inner?.let { arrayOf(it) }
    ?: (this as? DataClassPathSerializer<*>)?.inner?.let { arrayOf(it) }
    // Recurse into sealed subtypes so their serializers (and the types they reference) are collected.
    ?: sealedOptionsOrNull()?.map { it.serializer }?.toTypedArray()
    ?: arrayOf()

@OptIn(ExperimentalSerializationApi::class)
context(runtime: ServerRuntime)
public fun KSerializer<*>.decontextualize(): KSerializer<*> =
    if (descriptor.kind == SerialKind.CONTEXTUAL)
        runtime.internalSerialization.serializersModule.getContextual(
            descriptor.capturedKClass ?: throw IllegalStateException("No captured KClass found for $descriptor")
        )
            ?: throw IllegalStateException("No contextual serializer found for ${descriptor.capturedKClass!!.qualifiedName}")
    else this