@file:OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)

package com.lightningkite.ktordb

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
import kotlin.reflect.full.allSupertypes
import kotlin.reflect.jvm.jvmErasure

private val serializers = HashMap<KSerializer<*>, KSerializer<*>>()
private fun <Inner> getMod(inner: KSerializer<Inner>): KSerializer<Modification<Inner>> = serializers.getOrPut(inner) {
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
            register(Modification.AppendList.serializer(element))
            register(Modification.AppendSet.serializer(element))
            register(Modification.Remove.serializer(element))
            register(Modification.RemoveInstances.serializer(element))
            register(Modification.DropFirst.serializer(element))
            register(Modification.DropLast.serializer(element))
            register(Modification.PerElement.serializer(element))
        }
    }
    if (inner.descriptor.kind == StructureKind.MAP) {
        inner.mapValueElement()?.let { element ->
            register(Modification.Combine.serializer(element))
            register(Modification.ModifyByKey.serializer(element))
            register(Modification.RemoveKeys.serializer(element))
        }
    }
    if (inner is GeneratedSerializer<*> && inner.descriptor.kind == StructureKind.CLASS) {
        val childSerializers = inner.childSerializers()
        val type =
            inner::class.allSupertypes.find { it.classifier == GeneratedSerializer::class }!!.arguments[0].type!!.jvmErasure
        for (index in 0 until inner.descriptor.elementsCount) {
            val name = inner.descriptor.getElementName(index)
            val prop = inner.fields[name]
            register(
                OnFieldSerializer2<Any, Any?>(
                    inner as KSerializer<Any>,
                    childSerializers[index] as KSerializer<Any?>,
                    prop as DataClassProperty<Any, Any?>
                )
            )
        }
    }
    if (inner.descriptor.isNullable) {
        inner.nullElement()?.let { element ->
            register(Modification.IfNotNull.serializer(element))
        }
    }
    MySealedClassSerializer(
        "com.lightningkite.ktordb.Modification<${inner.descriptor.serialName}>",
        Modification::class as KClass<Modification<Inner>>,
        map
    ) {
        when (it) {
            is Modification.Chain -> "com.lightningkite.ktordb.Modification.Chain<${inner.descriptor.serialName}>"
            is Modification.IfNotNull<*> -> "com.lightningkite.ktordb.Modification.IfNotNull<${inner.descriptor.serialName.removeSuffix("?")}>"
            is Modification.Assign -> "com.lightningkite.ktordb.Modification.Assign<${inner.descriptor.serialName}>"
            is Modification.CoerceAtMost -> "com.lightningkite.ktordb.Modification.CoerceAtMost<${inner.descriptor.serialName}>"
            is Modification.CoerceAtLeast -> "com.lightningkite.ktordb.Modification.CoerceAtLeast<${inner.descriptor.serialName}>"
            is Modification.Increment -> "com.lightningkite.ktordb.Modification.Increment<${inner.descriptor.serialName}>"
            is Modification.Multiply -> "com.lightningkite.ktordb.Modification.Multiply<${inner.descriptor.serialName}>"
            is Modification.AppendString -> "com.lightningkite.ktordb.Modification.AppendString"
            is Modification.AppendList<*> -> "com.lightningkite.ktordb.Modification.AppendList<${inner.listElement()?.descriptor?.serialName}>"
            is Modification.AppendSet<*> -> "com.lightningkite.ktordb.Modification.AppendSet<${inner.listElement()?.descriptor?.serialName}>"
            is Modification.Remove<*> -> "com.lightningkite.ktordb.Modification.Remove<${inner.listElement()?.descriptor?.serialName}>"
            is Modification.RemoveInstances<*> -> "com.lightningkite.ktordb.Modification.RemoveInstances<${inner.listElement()?.descriptor?.serialName}>"
            is Modification.DropFirst<*> -> "com.lightningkite.ktordb.Modification.DropFirst<${inner.listElement()?.descriptor?.serialName}>"
            is Modification.DropLast<*> -> "com.lightningkite.ktordb.Modification.DropLast<${inner.listElement()?.descriptor?.serialName}>"
            is Modification.PerElement<*> -> "com.lightningkite.ktordb.Modification.PerElement"/*<${inner.listElement()?.descriptor?.serialName}>"*/
            is Modification.Combine<*> -> "com.lightningkite.ktordb.Modification.Combine<${inner.mapValueElement()?.descriptor?.serialName}>"
            is Modification.ModifyByKey<*> -> "com.lightningkite.ktordb.Modification.ModifyByKey<${inner.mapValueElement()?.descriptor?.serialName}>"
            is Modification.RemoveKeys<*> -> "com.lightningkite.ktordb.Modification.RemoveKeys<${inner.mapValueElement()?.descriptor?.serialName}>"
            is Modification.OnField<*, *> -> "com.lightningkite.ktordb.Modification.OnField<${inner.descriptor.serialName}>(${it.key.name})"
            else -> fatalError()
        }
    }
} as KSerializer<Modification<Inner>>

class ModificationSerializer<Inner>(inner: KSerializer<Inner>) : KSerializer<Modification<Inner>> by getMod(inner)

class OnFieldSerializer2<K : Any, V>(
    val outer: KSerializer<K>,
    val inner: KSerializer<V>,
    val field: DataClassProperty<K, V>,
) : KSerializer<Modification.OnField<K, V>> {
    val modificationSerializer = Modification.serializer(inner)
    override fun deserialize(decoder: Decoder): Modification.OnField<K, V> {
        return Modification.OnField(field, modification = decoder.decodeSerializableValue(modificationSerializer))
    }

    override val descriptor: SerialDescriptor = SerialDescriptor("com.lightningkite.ktordb.Modification.OnField<${outer.descriptor.serialName}>(${field.name})", modificationSerializer.descriptor)

    override fun serialize(encoder: Encoder, value: Modification.OnField<K, V>) {
        encoder.encodeSerializableValue(modificationSerializer, value.modification)
    }
}

class ModificationChainSerializer<T>(val inner: KSerializer<T>) : KSerializer<Modification.Chain<T>> {
    val to by lazy { ListSerializer(Modification.serializer(inner)) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Modification.Chain<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.Chain<T> = Modification.Chain(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Modification.Chain<T>) = encoder.encodeSerializableValue(to, value.modifications)
}
class ModificationIfNotNullSerializer<T>(val inner: KSerializer<T>) : KSerializer<Modification.IfNotNull<T>> {
    val to by lazy { Modification.serializer(inner) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Modification.IfNotNull<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.IfNotNull<T> = Modification.IfNotNull(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Modification.IfNotNull<T>) = encoder.encodeSerializableValue(to, value.modification)
}
class ModificationAssignSerializer<T>(val inner: KSerializer<T>) : KSerializer<Modification.Assign<T>> {
    val to by lazy { inner }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Modification.Assign<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.Assign<T> = Modification.Assign(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Modification.Assign<T>) = encoder.encodeSerializableValue(to, value.value)
}
class ModificationCoerceAtMostSerializer<T: Comparable<T>>(val inner: KSerializer<T>) : KSerializer<Modification.CoerceAtMost<T>> {
    val to by lazy { inner }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Modification.CoerceAtMost<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.CoerceAtMost<T> = Modification.CoerceAtMost(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Modification.CoerceAtMost<T>) = encoder.encodeSerializableValue(to, value.value)
}
class ModificationCoerceAtLeastSerializer<T: Comparable<T>>(val inner: KSerializer<T>) : KSerializer<Modification.CoerceAtLeast<T>> {
    val to by lazy { inner }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Modification.CoerceAtLeast<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.CoerceAtLeast<T> = Modification.CoerceAtLeast(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Modification.CoerceAtLeast<T>) = encoder.encodeSerializableValue(to, value.value)
}
class ModificationIncrementSerializer<T: Number>(val inner: KSerializer<T>) : KSerializer<Modification.Increment<T>> {
    val to by lazy { inner }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Modification.Increment<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.Increment<T> = Modification.Increment(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Modification.Increment<T>) = encoder.encodeSerializableValue(to, value.by)
}
class ModificationMultiplySerializer<T: Number>(val inner: KSerializer<T>) : KSerializer<Modification.Multiply<T>> {
    val to by lazy { inner }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Modification.Multiply<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.Multiply<T> = Modification.Multiply(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Modification.Multiply<T>) = encoder.encodeSerializableValue(to, value.by)
}
object ModificationAppendStringSerializer : KSerializer<Modification.AppendString> {
    val to by lazy { serializer<String>() }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Modification.AppendString") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.AppendString = Modification.AppendString(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Modification.AppendString) = encoder.encodeSerializableValue(to, value.value)
}
class ModificationAppendListSerializer<T>(val inner: KSerializer<T>) : KSerializer<Modification.AppendList<T>> {
    val to by lazy { ListSerializer(inner) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Modification.AppendList<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.AppendList<T> = Modification.AppendList(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Modification.AppendList<T>) = encoder.encodeSerializableValue(to, value.items)
}
class ModificationAppendSetSerializer<T>(val inner: KSerializer<T>) : KSerializer<Modification.AppendSet<T>> {
    val to by lazy { ListSerializer(inner) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Modification.AppendSet<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.AppendSet<T> = Modification.AppendSet(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Modification.AppendSet<T>) = encoder.encodeSerializableValue(to, value.items)
}
class ModificationRemoveSerializer<T>(val inner: KSerializer<T>) : KSerializer<Modification.Remove<T>> {
    val to by lazy { Condition.serializer(inner) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Modification.Remove<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.Remove<T> = Modification.Remove(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Modification.Remove<T>) = encoder.encodeSerializableValue(to, value.condition)
}
class ModificationRemoveInstancesSerializer<T>(val inner: KSerializer<T>) : KSerializer<Modification.RemoveInstances<T>> {
    val to by lazy { ListSerializer(inner) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Modification.RemoveInstances<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.RemoveInstances<T> = Modification.RemoveInstances(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Modification.RemoveInstances<T>) = encoder.encodeSerializableValue(to, value.items)
}
class ModificationCombineSerializer<T>(val inner: KSerializer<T>) : KSerializer<Modification.Combine<T>> {
    val to by lazy { MapSerializer(serializer<String>(), inner) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Modification.Combine<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.Combine<T> = Modification.Combine(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Modification.Combine<T>) = encoder.encodeSerializableValue(to, value.map)
}
class ModificationModifyByKeySerializer<T>(val inner: KSerializer<T>) : KSerializer<Modification.ModifyByKey<T>> {
    val to by lazy { MapSerializer(serializer<String>(), Modification.serializer(inner)) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Modification.ModifyByKey<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.ModifyByKey<T> = Modification.ModifyByKey(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Modification.ModifyByKey<T>) = encoder.encodeSerializableValue(to, value.map)
}
class ModificationRemoveKeysSerializer<T>(val inner: KSerializer<T>) : KSerializer<Modification.RemoveKeys<T>> {
    val to by lazy { SetSerializer(serializer<String>()) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Modification.RemoveKeys<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Modification.RemoveKeys<T> = Modification.RemoveKeys(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Modification.RemoveKeys<T>) = encoder.encodeSerializableValue(to, value.fields)
}
class ModificationDropFirstSerializer<T>(val inner: KSerializer<T>) : KSerializer<Modification.DropFirst<T>> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.lightningkite.ktordb.Modification.DropFirst<${inner.descriptor.serialName}>", PrimitiveKind.BOOLEAN)
    override fun deserialize(decoder: Decoder): Modification.DropFirst<T> {
        decoder.decodeBoolean()
        return Modification.DropFirst()
    }
    override fun serialize(encoder: Encoder, value: Modification.DropFirst<T>) = encoder.encodeBoolean(true)
}
class ModificationDropLastSerializer<T>(val inner: KSerializer<T>) : KSerializer<Modification.DropLast<T>> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.lightningkite.ktordb.Modification.DropLast<${inner.descriptor.serialName}>", PrimitiveKind.BOOLEAN)
    override fun deserialize(decoder: Decoder): Modification.DropLast<T> {
        decoder.decodeBoolean()
        return Modification.DropLast()
    }
    override fun serialize(encoder: Encoder, value: Modification.DropLast<T>) = encoder.encodeBoolean(true)
}

