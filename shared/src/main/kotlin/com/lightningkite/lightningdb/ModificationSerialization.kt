@file:OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)

package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.IsEquatable
import com.lightningkite.khrysalis.fatalError
import kotlinx.serialization.*
import kotlin.reflect.KClass
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.internal.GeneratedSerializer
import java.util.*
import kotlin.reflect.KProperty1
import kotlin.reflect.full.allSupertypes
import kotlin.reflect.jvm.jvmErasure

private val serializers = HashMap<KSerializer<*>, MySealedClassSerializerInterface<*>>()

@Suppress("UNCHECKED_CAST")
private fun <Inner> getMod(inner: KSerializer<Inner>): MySealedClassSerializerInterface<Modification<Inner>> = serializers.getOrPut(inner) {
    MySealedClassSerializer(
        "com.lightningkite.lightningdb.Modification<${inner.descriptor.serialName}>",
        Modification::class as KClass<Modification<Inner>>,
        {
            val map = LinkedHashMap<String, KSerializer<out Modification<Inner>>>()
            fun register(serializer: KSerializer<out Modification<*>>) {
                map[serializer.descriptor.serialName] = (serializer as KSerializer<out Modification<Inner>>)
            }
            register(Modification.Chain.serializer(inner))
            register(Modification.IfNotNull.serializer(inner))
            register(Modification.Assign.serializer(inner))
            register(Modification.CoerceAtMost.serializer(inner))
            register(Modification.CoerceAtLeast.serializer(inner))
            register(Modification.Increment.serializer(inner))
            register(Modification.Multiply.serializer(inner))
            if (inner == String.serializer()) register(Modification.AppendString.serializer())
            if (inner.descriptor.kind == StructureKind.LIST) {
                inner.listElement()?.let { element ->
                    register(Modification.SetAppend.serializer(element))
                    register(Modification.SetRemove.serializer(element))
                    register(Modification.SetRemoveInstances.serializer(element))
                    register(Modification.SetDropFirst.serializer(element))
                    register(Modification.SetDropLast.serializer(element))
                    register(Modification.SetPerElement.serializer(element))
                    register(Modification.ListAppend.serializer(element))
                    register(Modification.ListRemove.serializer(element))
                    register(Modification.ListRemoveInstances.serializer(element))
                    register(Modification.ListDropFirst.serializer(element))
                    register(Modification.ListDropLast.serializer(element))
                    register(Modification.ListPerElement.serializer(element))
                }
            }
            if (inner.descriptor.kind == StructureKind.MAP) {
                inner.mapValueElement()?.let { element ->
                    register(Modification.Combine.serializer(element))
                    register(Modification.ModifyByKey.serializer(element))
                    register(Modification.RemoveKeys.serializer(element))
                }
            }
            if (inner is GeneratedSerializer<*> && inner.descriptor.kind == StructureKind.CLASS && inner !is MySealedClassSerializerInterface) {
                val childSerializers = inner.childSerializers()
                val fields = inner.attemptGrabFields()
                for (index in 0 until inner.descriptor.elementsCount) {
                    val name = inner.descriptor.getElementName(index)
                    val prop = fields[name]!!.property
                    register(
                        OnFieldSerializer2<Any, Any?>(
                            prop as KProperty1<Any, Any?>,
                            Modification.serializer(childSerializers[index]) as KSerializer<Modification<Any?>>
                        )
                    )
                }
            }
            if (inner.descriptor.isNullable) {
                inner.nullElement()?.let { element ->
                    register(Modification.IfNotNull.serializer(element))
                }
            }
            map
        },
        annotations = Modification::class.annotations,
        alternateReadNames = mapOf(
            "AppendList" to "ListAppend",
            "AppendSet" to "SetAppend",
            "Remove" to "ListRemove",
            "RemoveInstances" to "ListRemoveInstances",
            "DropFirst" to "ListDropFirst",
            "DropLast" to "ListDropLast",
            "PerElement" to "ListPerElement",
        )
    ) {
        when (it) {
            is Modification.Chain -> "Chain"
            is Modification.IfNotNull<*> -> "IfNotNull"
            is Modification.Assign -> "Assign"
            is Modification.CoerceAtMost -> "CoerceAtMost"
            is Modification.CoerceAtLeast -> "CoerceAtLeast"
            is Modification.Increment -> "Increment"
            is Modification.Multiply -> "Multiply"
            is Modification.AppendString -> "AppendString"
            is Modification.ListAppend<*> -> "ListAppend"
            is Modification.ListRemove<*> -> "ListRemove"
            is Modification.ListRemoveInstances<*> -> "ListRemoveInstances"
            is Modification.ListDropFirst<*> -> "ListDropFirst"
            is Modification.ListDropLast<*> -> "ListDropLast"
            is Modification.ListPerElement<*> -> "ListPerElement"
            is Modification.SetAppend<*> -> "SetAppend"
            is Modification.SetRemove<*> -> "SetRemove"
            is Modification.SetRemoveInstances<*> -> "SetRemoveInstances"
            is Modification.SetDropFirst<*> -> "SetDropFirst"
            is Modification.SetDropLast<*> -> "SetDropLast"
            is Modification.SetPerElement<*> -> "SetPerElement"
            is Modification.Combine<*> -> "Combine"
            is Modification.ModifyByKey<*> -> "ModifyByKey"
            is Modification.RemoveKeys<*> -> "RemoveKeys"
            is Modification.OnField<*, *> -> it.key.name
            else -> fatalError()
        }
    }
} as MySealedClassSerializerInterface<Modification<Inner>>

class ModificationSerializer<Inner>(val inner: KSerializer<Inner>) : MySealedClassSerializerInterface<Modification<Inner>> by getMod(inner)

class OnFieldSerializer2<K : Any, V>(
    val field: KProperty1<K, V>,
    val modificationSerializer: KSerializer<Modification<V>>
) : KSerializer<Modification.OnField<K, V>> {
    override fun deserialize(decoder: Decoder): Modification.OnField<K, V> {
        return Modification.OnField(field, modification = decoder.decodeSerializableValue(modificationSerializer))
    }

    override val descriptor: SerialDescriptor = SerialDescriptor(field.name, modificationSerializer.descriptor)

    override fun serialize(encoder: Encoder, value: Modification.OnField<K, V>) {
        encoder.encodeSerializableValue(modificationSerializer, value.modification)
    }
}

class ModificationChainSerializer<T>(val inner: KSerializer<T>) : KSerializer<Modification.Chain<T>> {
    val to by lazy { ListSerializer(Modification.serializer(inner)) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("Chain") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.Chain<T> =
        Modification.Chain(decoder.decodeSerializableValue(to))

    override fun serialize(encoder: Encoder, value: Modification.Chain<T>) =
        encoder.encodeSerializableValue(to, value.modifications)
}

class ModificationIfNotNullSerializer<T>(val inner: KSerializer<T>) : KSerializer<Modification.IfNotNull<T>> {
    val to by lazy { Modification.serializer(inner) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("IfNotNull") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.IfNotNull<T> =
        Modification.IfNotNull(decoder.decodeSerializableValue(to))

    override fun serialize(encoder: Encoder, value: Modification.IfNotNull<T>) =
        encoder.encodeSerializableValue(to, value.modification)
}

class ModificationAssignSerializer<T>(val inner: KSerializer<T>) : KSerializer<Modification.Assign<T>> {
    val to by lazy { inner }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("Assign") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.Assign<T> =
        Modification.Assign(decoder.decodeSerializableValue(to))

    override fun serialize(encoder: Encoder, value: Modification.Assign<T>) =
        encoder.encodeSerializableValue(to, value.value)
}

class ModificationCoerceAtMostSerializer<T : Comparable<T>>(val inner: KSerializer<T>) :
    KSerializer<Modification.CoerceAtMost<T>> {
    val to by lazy { inner }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("CoerceAtMost") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.CoerceAtMost<T> =
        Modification.CoerceAtMost(decoder.decodeSerializableValue(to))

    override fun serialize(encoder: Encoder, value: Modification.CoerceAtMost<T>) =
        encoder.encodeSerializableValue(to, value.value)
}

class ModificationCoerceAtLeastSerializer<T : Comparable<T>>(val inner: KSerializer<T>) :
    KSerializer<Modification.CoerceAtLeast<T>> {
    val to by lazy { inner }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("CoerceAtLeast") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.CoerceAtLeast<T> =
        Modification.CoerceAtLeast(decoder.decodeSerializableValue(to))

    override fun serialize(encoder: Encoder, value: Modification.CoerceAtLeast<T>) =
        encoder.encodeSerializableValue(to, value.value)
}

class ModificationIncrementSerializer<T : Number>(val inner: KSerializer<T>) : KSerializer<Modification.Increment<T>> {
    val to by lazy { inner }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("Increment") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.Increment<T> =
        Modification.Increment(decoder.decodeSerializableValue(to))

    override fun serialize(encoder: Encoder, value: Modification.Increment<T>) =
        encoder.encodeSerializableValue(to, value.by)
}

class ModificationMultiplySerializer<T : Number>(val inner: KSerializer<T>) : KSerializer<Modification.Multiply<T>> {
    val to by lazy { inner }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("Multiply") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.Multiply<T> =
        Modification.Multiply(decoder.decodeSerializableValue(to))

    override fun serialize(encoder: Encoder, value: Modification.Multiply<T>) =
        encoder.encodeSerializableValue(to, value.by)
}

object ModificationAppendStringSerializer : KSerializer<Modification.AppendString> {
    val to by lazy { serializer<String>() }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("AppendString") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.AppendString =
        Modification.AppendString(decoder.decodeSerializableValue(to))

    override fun serialize(encoder: Encoder, value: Modification.AppendString) =
        encoder.encodeSerializableValue(to, value.value)
}

class ModificationListAppendSerializer<T>(val inner: KSerializer<T>) : KSerializer<Modification.ListAppend<T>> {
    val to by lazy { ListSerializer(inner) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("ListAppend") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.ListAppend<T> =
        Modification.ListAppend(decoder.decodeSerializableValue(to))

    override fun serialize(encoder: Encoder, value: Modification.ListAppend<T>) =
        encoder.encodeSerializableValue(to, value.items)
}

class ModificationSetAppendSerializer<T>(val inner: KSerializer<T>) : KSerializer<Modification.SetAppend<T>> {
    val to by lazy { SetSerializer(inner) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("SetAppend") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.SetAppend<T> =
        Modification.SetAppend(decoder.decodeSerializableValue(to))

    override fun serialize(encoder: Encoder, value: Modification.SetAppend<T>) =
        encoder.encodeSerializableValue(to, value.items)
}

class ModificationListRemoveSerializer<T>(val inner: KSerializer<T>) : KSerializer<Modification.ListRemove<T>> {
    val to by lazy { Condition.serializer(inner) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("ListRemove") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.ListRemove<T> =
        Modification.ListRemove(decoder.decodeSerializableValue(to))

    override fun serialize(encoder: Encoder, value: Modification.ListRemove<T>) =
        encoder.encodeSerializableValue(to, value.condition)
}

class ModificationSetRemoveSerializer<T>(val inner: KSerializer<T>) : KSerializer<Modification.SetRemove<T>> {
    val to by lazy { Condition.serializer(inner) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("SetRemove") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.SetRemove<T> =
        Modification.SetRemove(decoder.decodeSerializableValue(to))

    override fun serialize(encoder: Encoder, value: Modification.SetRemove<T>) =
        encoder.encodeSerializableValue(to, value.condition)
}

class ModificationListRemoveInstancesSerializer<T>(val inner: KSerializer<T>) :
    KSerializer<Modification.ListRemoveInstances<T>> {
    val to by lazy { ListSerializer(inner) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("ListRemoveInstances") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.ListRemoveInstances<T> =
        Modification.ListRemoveInstances(decoder.decodeSerializableValue(to))

    override fun serialize(encoder: Encoder, value: Modification.ListRemoveInstances<T>) =
        encoder.encodeSerializableValue(to, value.items)
}

class ModificationSetRemoveInstancesSerializer<T>(val inner: KSerializer<T>) :
    KSerializer<Modification.SetRemoveInstances<T>> {
    val to by lazy { SetSerializer(inner) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("SetRemoveInstances") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.SetRemoveInstances<T> =
        Modification.SetRemoveInstances(decoder.decodeSerializableValue(to))

    override fun serialize(encoder: Encoder, value: Modification.SetRemoveInstances<T>) =
        encoder.encodeSerializableValue(to, value.items)
}

class ModificationCombineSerializer<T>(val inner: KSerializer<T>) : KSerializer<Modification.Combine<T>> {
    val to by lazy { MapSerializer(serializer<String>(), inner) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("Combine") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.Combine<T> =
        Modification.Combine(decoder.decodeSerializableValue(to))

    override fun serialize(encoder: Encoder, value: Modification.Combine<T>) =
        encoder.encodeSerializableValue(to, value.map)
}

class ModificationModifyByKeySerializer<T>(val inner: KSerializer<T>) : KSerializer<Modification.ModifyByKey<T>> {
    val to by lazy { MapSerializer(serializer<String>(), Modification.serializer(inner)) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("ModifyByKey") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.ModifyByKey<T> =
        Modification.ModifyByKey(decoder.decodeSerializableValue(to))

    override fun serialize(encoder: Encoder, value: Modification.ModifyByKey<T>) =
        encoder.encodeSerializableValue(to, value.map)
}

class ModificationRemoveKeysSerializer<T>(val inner: KSerializer<T>) : KSerializer<Modification.RemoveKeys<T>> {
    val to by lazy { SetSerializer(serializer<String>()) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("RemoveKeys") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.RemoveKeys<T> =
        Modification.RemoveKeys(decoder.decodeSerializableValue(to))

    override fun serialize(encoder: Encoder, value: Modification.RemoveKeys<T>) =
        encoder.encodeSerializableValue(to, value.fields)
}

class ModificationListDropFirstSerializer<T>(val inner: KSerializer<T>) : KSerializer<Modification.ListDropFirst<T>> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ListDropFirst", PrimitiveKind.BOOLEAN)
    override fun deserialize(decoder: Decoder): Modification.ListDropFirst<T> {
        decoder.decodeBoolean()
        return Modification.ListDropFirst()
    }

    override fun serialize(encoder: Encoder, value: Modification.ListDropFirst<T>) = encoder.encodeBoolean(true)
}

class ModificationSetDropFirstSerializer<T>(val inner: KSerializer<T>) : KSerializer<Modification.SetDropFirst<T>> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SetDropFirst", PrimitiveKind.BOOLEAN)
    override fun deserialize(decoder: Decoder): Modification.SetDropFirst<T> {
        decoder.decodeBoolean()
        return Modification.SetDropFirst()
    }

    override fun serialize(encoder: Encoder, value: Modification.SetDropFirst<T>) = encoder.encodeBoolean(true)
}

class ModificationListDropLastSerializer<T>(val inner: KSerializer<T>) : KSerializer<Modification.ListDropLast<T>> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ListDropLast", PrimitiveKind.BOOLEAN)
    override fun deserialize(decoder: Decoder): Modification.ListDropLast<T> {
        decoder.decodeBoolean()
        return Modification.ListDropLast()
    }

    override fun serialize(encoder: Encoder, value: Modification.ListDropLast<T>) = encoder.encodeBoolean(true)
}

class ModificationSetDropLastSerializer<T>(val inner: KSerializer<T>) : KSerializer<Modification.SetDropLast<T>> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SetDropLast", PrimitiveKind.BOOLEAN)
    override fun deserialize(decoder: Decoder): Modification.SetDropLast<T> {
        decoder.decodeBoolean()
        return Modification.SetDropLast()
    }

    override fun serialize(encoder: Encoder, value: Modification.SetDropLast<T>) = encoder.encodeBoolean(true)
}

