package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.runtime.Engine
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
context(server: Engine)
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

/** One subtype of a sealed type: its on-the-wire discriminator [name] and its [serializer]. */
public class SealedOptionInfo(public val name: String, public val serializer: KSerializer<*>)

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
context(runtime: Engine)
public fun KSerializer<*>.decontextualize(): KSerializer<*> =
    if (descriptor.kind == SerialKind.CONTEXTUAL)
        runtime.internalSerialization.serializersModule.getContextual(
            descriptor.capturedKClass ?: throw IllegalStateException("No captured KClass found for $descriptor")
        )
            ?: throw IllegalStateException("No contextual serializer found for ${descriptor.capturedKClass!!.qualifiedName}")
    else this