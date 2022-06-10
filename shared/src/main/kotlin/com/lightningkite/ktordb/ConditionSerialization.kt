@file:OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)

package com.lightningkite.ktordb

import com.lightningkite.khrysalis.IsEquatable
import com.lightningkite.khrysalis.fatalError
import kotlinx.serialization.*
import kotlin.reflect.KClass
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.internal.GeneratedSerializer
import java.lang.IllegalStateException
import java.util.*
import kotlin.reflect.KProperty1
import kotlin.reflect.full.allSupertypes
import kotlin.reflect.jvm.jvmErasure

private val serializers = HashMap<KSerializer<*>, KSerializer<*>>()
private fun <Inner> getCond(inner: KSerializer<Inner>): KSerializer<Condition<Inner>> = serializers.getOrPut(inner) {
    val map = LinkedHashMap<String, KSerializer<out Condition<Inner>>>()
    fun register(serializer: KSerializer<out Condition<*>>) {
        map[serializer.descriptor.serialName] = (serializer as KSerializer<out Condition<Inner>>)
    }
    register(Condition.Never.serializer(inner))
    register(Condition.Always.serializer(inner))
    register(Condition.And.serializer(inner))
    register(Condition.Or.serializer(inner))
    register(Condition.Not.serializer(inner))
    register(Condition.Equal.serializer(inner))
    register(Condition.NotEqual.serializer(inner))
    register(Condition.Inside.serializer(inner))
    register(Condition.NotInside.serializer(inner))
    register(Condition.GreaterThan.serializer(inner))
    register(Condition.LessThan.serializer(inner))
    register(Condition.GreaterThanOrEqual.serializer(inner))
    register(Condition.LessThanOrEqual.serializer(inner))
    if (inner == String.serializer()) register(Condition.Search.serializer())
    if (inner == Int.serializer()) {
        register(Condition.IntBitsClear.serializer())
        register(Condition.IntBitsSet.serializer())
        register(Condition.IntBitsAnyClear.serializer())
        register(Condition.IntBitsAnySet.serializer())
    }
    if (inner.descriptor.kind == StructureKind.LIST) {
        inner.listElement()?.let { element ->
            register(Condition.AllElements.serializer(element))
            register(Condition.AnyElements.serializer(element))
            register(Condition.SizesEquals.serializer(element))
        }
    }
    if (inner.descriptor.kind == StructureKind.MAP) {
        inner.mapValueElement()?.let { element ->
            register(Condition.Exists.serializer(element))
            register(Condition.OnKey.serializer(element))
        }
    }
    if (inner is GeneratedSerializer<*> && inner.descriptor.kind == StructureKind.CLASS) {
        val childSerializers = inner.childSerializers()
        for (index in 0 until inner.descriptor.elementsCount) {
            val name = inner.descriptor.getElementName(index)
            val prop = inner.fields[name]
            register(
                OnFieldSerializer<Any, Any?>(
                    inner as KSerializer<Any>,
                    childSerializers[index] as KSerializer<Any?>,
                    prop as DataClassProperty<Any, Any?>
                )
            )
        }
    }
    if (inner.descriptor.isNullable) {
        inner.nullElement()?.let { element ->
            register(Condition.IfNotNull.serializer(element))
        }
    }
    MySealedClassSerializer(
        "com.lightningkite.ktordb.Condition<${inner.descriptor.serialName}>",
        Condition::class as KClass<Condition<Inner>>,
        map
    ) {
        when (it) {
            is Condition.Never -> "com.lightningkite.ktordb.Condition.Never<${inner.descriptor.serialName}>"
            is Condition.Always -> "com.lightningkite.ktordb.Condition.Always<${inner.descriptor.serialName}>"
            is Condition.And -> "com.lightningkite.ktordb.Condition.And<${inner.descriptor.serialName}>"
            is Condition.Or -> "com.lightningkite.ktordb.Condition.Or<${inner.descriptor.serialName}>"
            is Condition.Not -> "com.lightningkite.ktordb.Condition.Not<${inner.descriptor.serialName}>"
            is Condition.Equal -> "com.lightningkite.ktordb.Condition.Equal<${inner.descriptor.serialName}>"
            is Condition.NotEqual -> "com.lightningkite.ktordb.Condition.NotEqual<${inner.descriptor.serialName}>"
            is Condition.Inside -> "com.lightningkite.ktordb.Condition.Inside<${inner.descriptor.serialName}>"
            is Condition.NotInside -> "com.lightningkite.ktordb.Condition.NotInside<${inner.descriptor.serialName}>"
            is Condition.GreaterThan -> "com.lightningkite.ktordb.Condition.GreaterThan<${inner.descriptor.serialName}>"
            is Condition.LessThan -> "com.lightningkite.ktordb.Condition.LessThan<${inner.descriptor.serialName}>"
            is Condition.GreaterThanOrEqual -> "com.lightningkite.ktordb.Condition.GreaterThanOrEqual<${inner.descriptor.serialName}>"
            is Condition.LessThanOrEqual -> "com.lightningkite.ktordb.Condition.LessThanOrEqual<${inner.descriptor.serialName}>"
            is Condition.Search -> "com.lightningkite.ktordb.Condition.Search"
            is Condition.IntBitsClear -> "com.lightningkite.ktordb.Condition.IntBitsClear"
            is Condition.IntBitsSet -> "com.lightningkite.ktordb.Condition.IntBitsSet"
            is Condition.IntBitsAnyClear -> "com.lightningkite.ktordb.Condition.IntBitsAnyClear"
            is Condition.IntBitsAnySet -> "com.lightningkite.ktordb.Condition.IntBitsAnySet"
            is Condition.AllElements<*> -> "com.lightningkite.ktordb.Condition.AllElements<${inner.listElement()?.descriptor?.serialName}>"
            is Condition.AnyElements<*> -> "com.lightningkite.ktordb.Condition.AnyElements<${inner.listElement()?.descriptor?.serialName}>"
            is Condition.SizesEquals<*> -> "com.lightningkite.ktordb.Condition.SizesEquals<${inner.listElement()?.descriptor?.serialName}>"
            is Condition.Exists<*> -> "com.lightningkite.ktordb.Condition.Exists<${inner.mapValueElement()?.descriptor?.serialName}>"
            is Condition.OnKey<*> -> "com.lightningkite.ktordb.Condition.OnKey<${inner.mapValueElement()?.descriptor?.serialName}>"
            is Condition.IfNotNull<*> -> "com.lightningkite.ktordb.Condition.IfNotNull<${inner.descriptor.serialName.removeSuffix("?")}>"
            is Condition.OnField<*, *> -> "com.lightningkite.ktordb.Condition.OnField<${inner.descriptor.serialName}>(${it.key.name})"
            else -> fatalError()
        }
    }
} as KSerializer<Condition<Inner>>

class ConditionSerializer<Inner>(inner: KSerializer<Inner>) : KSerializer<Condition<Inner>> by getCond(inner)

class OnFieldSerializer<K : Any, V>(
    val outer: KSerializer<K>,
    val inner: KSerializer<V>,
    val field: DataClassProperty<K, V>
) : KSerializer<Condition.OnField<K, V>> {
    val conditionSerializer = ConditionSerializer(inner)
    override fun deserialize(decoder: Decoder): Condition.OnField<K, V> {
        return Condition.OnField(field, condition = decoder.decodeSerializableValue(conditionSerializer))
    }

    override val descriptor: SerialDescriptor = SerialDescriptor("com.lightningkite.ktordb.Condition.OnField<${outer.descriptor.serialName}>(${field.name})", conditionSerializer.descriptor)

    override fun serialize(encoder: Encoder, value: Condition.OnField<K, V>) {
        encoder.encodeSerializableValue(conditionSerializer, value.condition)
    }

}

class LazyRenamedSerialDescriptor(override val serialName: String, val getter: ()->SerialDescriptor): SerialDescriptor {
    override val elementsCount: Int get() = getter().elementsCount
    override val kind: SerialKind get() = getter().kind
    override fun getElementAnnotations(index: Int): List<Annotation> = getter().getElementAnnotations(index)
    override fun getElementDescriptor(index: Int): SerialDescriptor = getter().getElementDescriptor(index)
    override fun getElementIndex(name: String): Int = getter().getElementIndex(name)
    override fun getElementName(index: Int): String = getter().getElementName(index)
    override fun isElementOptional(index: Int): Boolean = getter().isElementOptional(index)
}


class ConditionNeverSerializer<T>(val inner: KSerializer<T>) : KSerializer<Condition.Never<T>> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.lightningkite.ktordb.Condition.Never<${inner.descriptor.serialName}>", PrimitiveKind.BOOLEAN)
    override fun deserialize(decoder: Decoder): Condition.Never<T> {
        decoder.decodeBoolean()
        return Condition.Never()
    }
    override fun serialize(encoder: Encoder, value: Condition.Never<T>) = encoder.encodeBoolean(true)
}
class ConditionAlwaysSerializer<T>(val inner: KSerializer<T>) : KSerializer<Condition.Always<T>> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.lightningkite.ktordb.Condition.Always<${inner.descriptor.serialName}>", PrimitiveKind.BOOLEAN)
    override fun deserialize(decoder: Decoder): Condition.Always<T> {
        decoder.decodeBoolean()
        return Condition.Always()
    }
    override fun serialize(encoder: Encoder, value: Condition.Always<T>) = encoder.encodeBoolean(true)
}
class ConditionAndSerializer<T>(val inner: KSerializer<T>) : KSerializer<Condition.And<T>> {
    val to by lazy { ListSerializer(Condition.serializer(inner)) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Condition.And<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.And<T> = Condition.And(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.And<T>) = encoder.encodeSerializableValue(to, value.conditions)
}
class ConditionOrSerializer<T>(val inner: KSerializer<T>) : KSerializer<Condition.Or<T>> {
    val to by lazy { ListSerializer(Condition.serializer(inner)) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Condition.Or<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.Or<T> = Condition.Or(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.Or<T>) = encoder.encodeSerializableValue(to, value.conditions)
}
class ConditionNotSerializer<T>(val inner: KSerializer<T>) : KSerializer<Condition.Not<T>> {
    val to by lazy { Condition.serializer(inner) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Condition.Not<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.Not<T> = Condition.Not(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.Not<T>) = encoder.encodeSerializableValue(to, value.condition)
}
class ConditionEqualSerializer<T: IsEquatable>(val inner: KSerializer<T>) : KSerializer<Condition.Equal<T>> {
    val to by lazy { inner }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Condition.Equal<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.Equal<T> = Condition.Equal(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.Equal<T>) = encoder.encodeSerializableValue(to, value.value)
}
class ConditionNotEqualSerializer<T: IsEquatable>(val inner: KSerializer<T>) : KSerializer<Condition.NotEqual<T>> {
    val to by lazy { inner }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Condition.NotEqual<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.NotEqual<T> = Condition.NotEqual(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.NotEqual<T>) = encoder.encodeSerializableValue(to, value.value)
}
class ConditionInsideSerializer<T: IsEquatable>(val inner: KSerializer<T>) : KSerializer<Condition.Inside<T>> {
    val to by lazy { ListSerializer(inner) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Condition.Inside<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.Inside<T> = Condition.Inside(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.Inside<T>) = encoder.encodeSerializableValue(to, value.values)
}
class ConditionNotInsideSerializer<T: IsEquatable>(val inner: KSerializer<T>) : KSerializer<Condition.NotInside<T>> {
    val to by lazy { ListSerializer(inner) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Condition.NotInside<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.NotInside<T> = Condition.NotInside(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.NotInside<T>) = encoder.encodeSerializableValue(to, value.values)
}
class ConditionGreaterThanSerializer<T: Comparable<T>>(val inner: KSerializer<T>) : KSerializer<Condition.GreaterThan<T>> {
    val to by lazy { inner }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Condition.GreaterThan<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.GreaterThan<T> = Condition.GreaterThan(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.GreaterThan<T>) = encoder.encodeSerializableValue(to, value.value)
}
class ConditionLessThanSerializer<T: Comparable<T>>(val inner: KSerializer<T>) : KSerializer<Condition.LessThan<T>> {
    val to by lazy { inner }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Condition.LessThan<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.LessThan<T> = Condition.LessThan(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.LessThan<T>) = encoder.encodeSerializableValue(to, value.value)
}
class ConditionGreaterThanOrEqualSerializer<T: Comparable<T>>(val inner: KSerializer<T>) : KSerializer<Condition.GreaterThanOrEqual<T>> {
    val to by lazy { inner }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Condition.GreaterThanOrEqual<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.GreaterThanOrEqual<T> = Condition.GreaterThanOrEqual(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.GreaterThanOrEqual<T>) = encoder.encodeSerializableValue(to, value.value)
}
class ConditionLessThanOrEqualSerializer<T: Comparable<T>>(val inner: KSerializer<T>) : KSerializer<Condition.LessThanOrEqual<T>> {
    val to by lazy { inner }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Condition.LessThanOrEqual<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.LessThanOrEqual<T> = Condition.LessThanOrEqual(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.LessThanOrEqual<T>) = encoder.encodeSerializableValue(to, value.value)
}
object ConditionIntBitsClearSerializer : KSerializer<Condition.IntBitsClear> {
    val to by lazy { serializer<Int>() }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Condition.IntBitsClear") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.IntBitsClear = Condition.IntBitsClear(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.IntBitsClear) = encoder.encodeSerializableValue(to, value.mask)
}
object ConditionIntBitsSetSerializer : KSerializer<Condition.IntBitsSet> {
    val to by lazy { serializer<Int>() }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Condition.IntBitsSet") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.IntBitsSet = Condition.IntBitsSet(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.IntBitsSet) = encoder.encodeSerializableValue(to, value.mask)
}
object ConditionIntBitsAnyClearSerializer : KSerializer<Condition.IntBitsAnyClear> {
    val to by lazy { serializer<Int>() }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Condition.IntBitsAnyClear") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.IntBitsAnyClear = Condition.IntBitsAnyClear(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.IntBitsAnyClear) = encoder.encodeSerializableValue(to, value.mask)
}
object ConditionIntBitsAnySetSerializer : KSerializer<Condition.IntBitsAnySet> {
    val to by lazy { serializer<Int>() }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Condition.IntBitsAnySet") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.IntBitsAnySet = Condition.IntBitsAnySet(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.IntBitsAnySet) = encoder.encodeSerializableValue(to, value.mask)
}
class ConditionAllElementsSerializer<T>(val inner: KSerializer<T>) : KSerializer<Condition.AllElements<T>> {
    val to by lazy { Condition.serializer(inner) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Condition.AllElements<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.AllElements<T> = Condition.AllElements(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.AllElements<T>) = encoder.encodeSerializableValue(to, value.condition)
}
class ConditionAnyElementsSerializer<T>(val inner: KSerializer<T>) : KSerializer<Condition.AnyElements<T>> {
    val to by lazy { Condition.serializer(inner) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Condition.AnyElements<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.AnyElements<T> = Condition.AnyElements(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.AnyElements<T>) = encoder.encodeSerializableValue(to, value.condition)
}
class ConditionExistsSerializer<T>(val inner: KSerializer<T>) : KSerializer<Condition.Exists<T>> {
    val to by lazy { serializer<String>() }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Condition.Exists<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.Exists<T> = Condition.Exists(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.Exists<T>) = encoder.encodeSerializableValue(to, value.key)
}
class ConditionSizesEqualsSerializer<T>(val inner: KSerializer<T>) : KSerializer<Condition.SizesEquals<T>> {
    val to by lazy { serializer<Int>() }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Condition.SizesEquals<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.SizesEquals<T> = Condition.SizesEquals(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.SizesEquals<T>) = encoder.encodeSerializableValue(to, value.count)
}
class ConditionIfNotNullSerializer<T>(val inner: KSerializer<T>) : KSerializer<Condition.IfNotNull<T>> {
    val to by lazy { Condition.serializer(inner) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("com.lightningkite.ktordb.Condition.IfNotNull<${inner.descriptor.serialName}>") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.IfNotNull<T> = Condition.IfNotNull(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.IfNotNull<T>) = encoder.encodeSerializableValue(to, value.condition)
}