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

fun <K, V> KSerializer<K>.fieldSerializer(property: KProperty1<K, V>): KSerializer<V>? {
    val index = this.descriptor.elementNames.indexOf(property.name)
    @Suppress("UNCHECKED_CAST")
    return (this as? GeneratedSerializer<*>)?.childSerializers()?.get(index) as? KSerializer<V>
}

private val serializers = HashMap<KSerializer<*>, KSerializer<*>>()
@Suppress("UNCHECKED_CAST")
private fun <Inner> getCond(inner: KSerializer<Inner>): KSerializer<Condition<Inner>> = serializers.getOrPut(inner) {
    MySealedClassSerializer(
        "com.lightningkite.ktordb.Condition<${inner.descriptor.serialName}>",
        Condition::class as KClass<Condition<Inner>>,
        {
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
                val fields = inner.attemptGrabFields()
                for (index in 0 until inner.descriptor.elementsCount) {
                    val name = inner.descriptor.getElementName(index)
                    val prop = fields[name]!!
                    register(
                        OnFieldSerializer<Any, Any?>(
                            prop.property as KProperty1<Any, Any?>,
                            Condition.serializer(childSerializers[index]) as KSerializer<Condition<Any?>>
                        )
                    )
                }
            }
            if (inner.descriptor.isNullable) {
                inner.nullElement()?.let { element ->
                    register(Condition.IfNotNull.serializer(element))
                }
            }
            map
        }
    ) {
        when (it) {
            is Condition.Never -> "Never"
            is Condition.Always -> "Always"
            is Condition.And -> "And"
            is Condition.Or -> "Or"
            is Condition.Not -> "Not"
            is Condition.Equal -> "Equal"
            is Condition.NotEqual -> "NotEqual"
            is Condition.Inside -> "Inside"
            is Condition.NotInside -> "NotInside"
            is Condition.GreaterThan -> "GreaterThan"
            is Condition.LessThan -> "LessThan"
            is Condition.GreaterThanOrEqual -> "GreaterThanOrEqual"
            is Condition.LessThanOrEqual -> "LessThanOrEqual"
            is Condition.Search -> "Search"
            is Condition.IntBitsClear -> "IntBitsClear"
            is Condition.IntBitsSet -> "IntBitsSet"
            is Condition.IntBitsAnyClear -> "IntBitsAnyClear"
            is Condition.IntBitsAnySet -> "IntBitsAnySet"
            is Condition.AllElements<*> -> "AllElements"
            is Condition.AnyElements<*> -> "AnyElements"
            is Condition.SizesEquals<*> -> "SizesEquals"
            is Condition.Exists<*> -> "Exists"
            is Condition.OnKey<*> -> "OnKey"
            is Condition.IfNotNull<*> -> "IfNotNull"
            is Condition.OnField<*, *> -> it.key.name
            else -> fatalError()
        }
    }
} as KSerializer<Condition<Inner>>

class ConditionSerializer<Inner>(inner: KSerializer<Inner>) : KSerializer<Condition<Inner>> by getCond(inner)

class OnFieldSerializer<K : Any, V>(
    val field: KProperty1<K, V>,
    val conditionSerializer: KSerializer<Condition<V>>
) : KSerializer<Condition.OnField<K, V>> {
    override fun deserialize(decoder: Decoder): Condition.OnField<K, V> {
        return Condition.OnField(field, condition = decoder.decodeSerializableValue(conditionSerializer))
    }

    override val descriptor: SerialDescriptor = SerialDescriptor(field.name, conditionSerializer.descriptor)

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
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Never", PrimitiveKind.BOOLEAN)
    override fun deserialize(decoder: Decoder): Condition.Never<T> {
        decoder.decodeBoolean()
        return Condition.Never()
    }
    override fun serialize(encoder: Encoder, value: Condition.Never<T>) = encoder.encodeBoolean(true)
}
class ConditionAlwaysSerializer<T>(val inner: KSerializer<T>) : KSerializer<Condition.Always<T>> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Always", PrimitiveKind.BOOLEAN)
    override fun deserialize(decoder: Decoder): Condition.Always<T> {
        decoder.decodeBoolean()
        return Condition.Always()
    }
    override fun serialize(encoder: Encoder, value: Condition.Always<T>) = encoder.encodeBoolean(true)
}
class ConditionAndSerializer<T>(val inner: KSerializer<T>) : KSerializer<Condition.And<T>> {
    val to by lazy { ListSerializer(Condition.serializer(inner)) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("And") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.And<T> = Condition.And(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.And<T>) = encoder.encodeSerializableValue(to, value.conditions)
}
class ConditionOrSerializer<T>(val inner: KSerializer<T>) : KSerializer<Condition.Or<T>> {
    val to by lazy { ListSerializer(Condition.serializer(inner)) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("Or") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.Or<T> = Condition.Or(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.Or<T>) = encoder.encodeSerializableValue(to, value.conditions)
}
class ConditionNotSerializer<T>(val inner: KSerializer<T>) : KSerializer<Condition.Not<T>> {
    val to by lazy { Condition.serializer(inner) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("Not") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.Not<T> = Condition.Not(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.Not<T>) = encoder.encodeSerializableValue(to, value.condition)
}
class ConditionEqualSerializer<T: IsEquatable>(val inner: KSerializer<T>) : KSerializer<Condition.Equal<T>> {
    val to by lazy { inner }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("Equal") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.Equal<T> = Condition.Equal(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.Equal<T>) = encoder.encodeSerializableValue(to, value.value)
}
class ConditionNotEqualSerializer<T: IsEquatable>(val inner: KSerializer<T>) : KSerializer<Condition.NotEqual<T>> {
    val to by lazy { inner }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("NotEqual") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.NotEqual<T> = Condition.NotEqual(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.NotEqual<T>) = encoder.encodeSerializableValue(to, value.value)
}
class ConditionInsideSerializer<T: IsEquatable>(val inner: KSerializer<T>) : KSerializer<Condition.Inside<T>> {
    val to by lazy { ListSerializer(inner) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("Inside") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.Inside<T> = Condition.Inside(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.Inside<T>) = encoder.encodeSerializableValue(to, value.values)
}
class ConditionNotInsideSerializer<T: IsEquatable>(val inner: KSerializer<T>) : KSerializer<Condition.NotInside<T>> {
    val to by lazy { ListSerializer(inner) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("NotInside") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.NotInside<T> = Condition.NotInside(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.NotInside<T>) = encoder.encodeSerializableValue(to, value.values)
}
class ConditionGreaterThanSerializer<T: Comparable<T>>(val inner: KSerializer<T>) : KSerializer<Condition.GreaterThan<T>> {
    val to by lazy { inner }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("GreaterThan") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.GreaterThan<T> = Condition.GreaterThan(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.GreaterThan<T>) = encoder.encodeSerializableValue(to, value.value)
}
class ConditionLessThanSerializer<T: Comparable<T>>(val inner: KSerializer<T>) : KSerializer<Condition.LessThan<T>> {
    val to by lazy { inner }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("LessThan") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.LessThan<T> = Condition.LessThan(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.LessThan<T>) = encoder.encodeSerializableValue(to, value.value)
}
class ConditionGreaterThanOrEqualSerializer<T: Comparable<T>>(val inner: KSerializer<T>) : KSerializer<Condition.GreaterThanOrEqual<T>> {
    val to by lazy { inner }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("GreaterThanOrEqual") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.GreaterThanOrEqual<T> = Condition.GreaterThanOrEqual(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.GreaterThanOrEqual<T>) = encoder.encodeSerializableValue(to, value.value)
}
class ConditionLessThanOrEqualSerializer<T: Comparable<T>>(val inner: KSerializer<T>) : KSerializer<Condition.LessThanOrEqual<T>> {
    val to by lazy { inner }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("LessThanOrEqual") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.LessThanOrEqual<T> = Condition.LessThanOrEqual(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.LessThanOrEqual<T>) = encoder.encodeSerializableValue(to, value.value)
}
object ConditionIntBitsClearSerializer : KSerializer<Condition.IntBitsClear> {
    val to by lazy { serializer<Int>() }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("IntBitsClear") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.IntBitsClear = Condition.IntBitsClear(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.IntBitsClear) = encoder.encodeSerializableValue(to, value.mask)
}
object ConditionIntBitsSetSerializer : KSerializer<Condition.IntBitsSet> {
    val to by lazy { serializer<Int>() }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("IntBitsSet") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.IntBitsSet = Condition.IntBitsSet(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.IntBitsSet) = encoder.encodeSerializableValue(to, value.mask)
}
object ConditionIntBitsAnyClearSerializer : KSerializer<Condition.IntBitsAnyClear> {
    val to by lazy { serializer<Int>() }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("IntBitsAnyClear") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.IntBitsAnyClear = Condition.IntBitsAnyClear(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.IntBitsAnyClear) = encoder.encodeSerializableValue(to, value.mask)
}
object ConditionIntBitsAnySetSerializer : KSerializer<Condition.IntBitsAnySet> {
    val to by lazy { serializer<Int>() }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("IntBitsAnySet") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.IntBitsAnySet = Condition.IntBitsAnySet(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.IntBitsAnySet) = encoder.encodeSerializableValue(to, value.mask)
}
class ConditionAllElementsSerializer<T>(val inner: KSerializer<T>) : KSerializer<Condition.AllElements<T>> {
    val to by lazy { Condition.serializer(inner) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("AllElements") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.AllElements<T> = Condition.AllElements(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.AllElements<T>) = encoder.encodeSerializableValue(to, value.condition)
}
class ConditionAnyElementsSerializer<T>(val inner: KSerializer<T>) : KSerializer<Condition.AnyElements<T>> {
    val to by lazy { Condition.serializer(inner) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("AnyElements") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.AnyElements<T> = Condition.AnyElements(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.AnyElements<T>) = encoder.encodeSerializableValue(to, value.condition)
}
class ConditionExistsSerializer<T>(val inner: KSerializer<T>) : KSerializer<Condition.Exists<T>> {
    val to by lazy { serializer<String>() }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("Exists") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.Exists<T> = Condition.Exists(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.Exists<T>) = encoder.encodeSerializableValue(to, value.key)
}
class ConditionSizesEqualsSerializer<T>(val inner: KSerializer<T>) : KSerializer<Condition.SizesEquals<T>> {
    val to by lazy { serializer<Int>() }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("SizesEquals") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.SizesEquals<T> = Condition.SizesEquals(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.SizesEquals<T>) = encoder.encodeSerializableValue(to, value.count)
}
class ConditionIfNotNullSerializer<T>(val inner: KSerializer<T>) : KSerializer<Condition.IfNotNull<T>> {
    val to by lazy { Condition.serializer(inner) }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("IfNotNull") { to.descriptor }
    override fun deserialize(decoder: Decoder): Condition.IfNotNull<T> = Condition.IfNotNull(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: Condition.IfNotNull<T>) = encoder.encodeSerializableValue(to, value.condition)
}