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

public fun KSerializer<*>.isUnit(): Boolean = descriptor.serialName == "kotlin.Unit"


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