@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.lightningdb

import com.lightningkite.GeoCoordinate
import com.lightningkite.khrysalis.IsEquatable
import com.lightningkite.khrysalis.fatalError
import kotlinx.serialization.*
import kotlinx.serialization.builtins.*
import kotlin.reflect.KClass
import kotlinx.serialization.descriptors.*

import com.lightningkite.lightningdb.SerializableProperty

private fun <T> commonOptions(inner: KSerializer<T>): List<MySealedClassSerializer.Option<Condition<T>, *>> = listOf(
    MySealedClassSerializer.Option(Condition.Never.serializer(inner)) { it is Condition.Never },
    MySealedClassSerializer.Option(Condition.Always.serializer(inner)) { it is Condition.Always },
    MySealedClassSerializer.Option(Condition.And.serializer(inner)) { it is Condition.And },
    MySealedClassSerializer.Option(Condition.Or.serializer(inner)) { it is Condition.Or },
    MySealedClassSerializer.Option(Condition.Not.serializer(inner)) { it is Condition.Not },
    MySealedClassSerializer.Option(Condition.Equal.serializer(inner)) { it is Condition.Equal },
    MySealedClassSerializer.Option(Condition.NotEqual.serializer(inner)) { it is Condition.NotEqual },
    MySealedClassSerializer.Option(Condition.Inside.serializer(inner)) { it is Condition.Inside },
    MySealedClassSerializer.Option(Condition.NotInside.serializer(inner)) { it is Condition.NotInside },
)
private fun <T: Any> nullableOptions(inner: KSerializer<T>): List<MySealedClassSerializer.Option<Condition<T?>, *>> = commonOptions(inner.nullable) + listOf(
    MySealedClassSerializer.Option(Condition.IfNotNull.serializer(inner)) { it is Condition.IfNotNull },
)
private fun <T: Comparable<T>> comparableOptions(inner: KSerializer<T>): List<MySealedClassSerializer.Option<Condition<T>, *>> = commonOptions(inner) + listOf(
    MySealedClassSerializer.Option(Condition.GreaterThan.serializer(inner)) { it is Condition.GreaterThan },
    MySealedClassSerializer.Option(Condition.LessThan.serializer(inner)) { it is Condition.LessThan },
    MySealedClassSerializer.Option(Condition.GreaterThanOrEqual.serializer(inner)) { it is Condition.GreaterThanOrEqual },
    MySealedClassSerializer.Option(Condition.LessThanOrEqual.serializer(inner)) { it is Condition.LessThanOrEqual },
)
private fun <T> listOptions(element: KSerializer<T>): List<MySealedClassSerializer.Option<Condition<List<T>>, *>>  = commonOptions(ListSerializer(element)) + listOf(
    MySealedClassSerializer.Option(Condition.ListAllElements.serializer(element), setOf("SetAllElements", "AllElements")) { it is Condition.ListAllElements },
    MySealedClassSerializer.Option(Condition.ListAnyElements.serializer(element), setOf("SetAnyElements", "AnyElements")) { it is Condition.ListAnyElements },
    MySealedClassSerializer.Option(Condition.ListSizesEquals.serializer(element), setOf("SetSizesEquals", "SizesEquals")) { it is Condition.ListSizesEquals },
)
private fun <T> setOptions(element: KSerializer<T>): List<MySealedClassSerializer.Option<Condition<Set<T>>, *>>  = commonOptions(SetSerializer(element)) + listOf(
    MySealedClassSerializer.Option(Condition.SetAllElements.serializer(element), setOf("ListAllElements", "AllElements")) { it is Condition.SetAllElements },
    MySealedClassSerializer.Option(Condition.SetAnyElements.serializer(element), setOf("ListAnyElements", "AnyElements")) { it is Condition.SetAnyElements },
    MySealedClassSerializer.Option(Condition.SetSizesEquals.serializer(element), setOf("ListSizesEquals", "SizesEquals")) { it is Condition.SetSizesEquals },
)
private fun <T> stringMapOptions(element: KSerializer<T>): List<MySealedClassSerializer.Option<Condition<Map<String, T>>, *>>  = commonOptions(
    MapSerializer(String.serializer(), element)
) + listOf(
    MySealedClassSerializer.Option(Condition.Exists.serializer(element)) { it is Condition.Exists },
    MySealedClassSerializer.Option(Condition.OnKey.serializer(element)) { it is Condition.OnKey },
)
private val intOptions: List<MySealedClassSerializer.Option<Condition<Int>, *>>  = comparableOptions(Int.serializer()) + listOf(
    MySealedClassSerializer.Option(Condition.IntBitsClear.serializer()) { it is Condition.IntBitsClear },
    MySealedClassSerializer.Option(Condition.IntBitsSet.serializer()) { it is Condition.IntBitsSet },
    MySealedClassSerializer.Option(Condition.IntBitsAnyClear.serializer()) { it is Condition.IntBitsAnyClear },
    MySealedClassSerializer.Option(Condition.IntBitsAnySet.serializer()) { it is Condition.IntBitsAnySet },
)
private val geocoordinateOptions: List<MySealedClassSerializer.Option<Condition<GeoCoordinate>, *>> = commonOptions(ContextualSerializer(GeoCoordinate::class)) + listOf(
    MySealedClassSerializer.Option(Condition.GeoDistance.serializer()) { it is Condition.GeoDistance },
)
private val stringOptions: List<MySealedClassSerializer.Option<Condition<String>, *>>  = comparableOptions(String.serializer()) + listOf(
    MySealedClassSerializer.Option(Condition.StringContains.serializer(), setOf("Search")) { it is Condition.StringContains },
    MySealedClassSerializer.Option(Condition.RegexMatches.serializer()) { it is Condition.RegexMatches },
)
private fun <T: Any> classOptionsReflective(inner: KSerializer<T>): List<MySealedClassSerializer.Option<Condition<T>, *>> =
    (commonOptions(inner) + inner.serializableProperties!!.let {
        it.mapIndexed { index, ser ->
            MySealedClassSerializer.Option<Condition<T>, Condition.OnField<T, Any?>>(ConditionOnFieldSerializer(
                ser as SerializableProperty<T, Any?>
            )) { it is Condition.OnField<*, *> && it.key.name == inner.descriptor.getElementName(index) }
        }
    } + MySealedClassSerializer.Option<Condition<T>, Condition.FullTextSearch<T>>(Condition.FullTextSearch.serializer(inner)) { it is Condition.FullTextSearch<*> }) as List<MySealedClassSerializer.Option<Condition<T>, *>>

private val cache = HashMap<KSerializerKey, MySealedClassSerializerInterface<*>>()
@Suppress("UNCHECKED_CAST")
class ConditionSerializer<T>(val inner: KSerializer<T>): MySealedClassSerializerInterface<Condition<T>> by (cache.getOrPut(KSerializerKey(inner)) {
    MySealedClassSerializer<Condition<T>>("com.lightningkite.lightningdb.Condition", {
        val r = when {
            inner.descriptor.isNullable -> nullableOptions(inner.innerElement() as KSerializer<Any>)
            inner.descriptor.serialName == "kotlin.String" -> stringOptions
            inner.descriptor.serialName == "kotlin.Int" -> intOptions
            inner.descriptor.serialName == "com.lightningkite.GeoCoordinate" -> geocoordinateOptions
            inner.descriptor.kind == StructureKind.MAP -> stringMapOptions(inner.innerElement2())
            inner.descriptor.kind == StructureKind.LIST -> {
                if(inner.descriptor.serialName.contains("Set")) setOptions(inner.innerElement())
                else listOptions(inner.innerElement())
            }
            inner.serializableProperties != null -> classOptionsReflective(inner as KSerializer<Any>)
            else -> comparableOptions(inner as KSerializer<String>)
        }
        r as List<MySealedClassSerializer.Option<Condition<T>, out Condition<T>>>
    })
} as MySealedClassSerializerInterface<Condition<T>>)

class ConditionOnFieldSerializer<K : Any, V>(
    val field: SerializableProperty<K, V>
) : WrappingSerializer<Condition.OnField<K, V>, Condition<V>>(field.name) {
    override fun getDeferred(): KSerializer<Condition<V>> = Condition.serializer(field.serializer)
    override fun inner(it: Condition.OnField<K, V>): Condition<V> = it.condition
    override fun outer(it: Condition<V>): Condition.OnField<K, V> = Condition.OnField(field, it)
}

class LazyRenamedSerialDescriptor(override val serialName: String, val getter: () -> SerialDescriptor) :
    SerialDescriptor {
    override val elementsCount: Int get() = getter().elementsCount
    override val kind: SerialKind get() = getter().kind
    override fun getElementAnnotations(index: Int): List<Annotation> = getter().getElementAnnotations(index)
    override fun getElementDescriptor(index: Int): SerialDescriptor = getter().getElementDescriptor(index)
    override fun getElementIndex(name: String): Int = getter().getElementIndex(name)
    override fun getElementName(index: Int): String = getter().getElementName(index)
    override fun isElementOptional(index: Int): Boolean = getter().isElementOptional(index)
}


class ConditionNeverSerializer<T>(val inner: KSerializer<T>) :
    WrappingSerializer<Condition.Never<T>, Boolean>("Never") {
    override fun getDeferred(): KSerializer<Boolean> = Boolean.serializer()
    override fun inner(it: Condition.Never<T>): Boolean = true
    override fun outer(it: Boolean) = Condition.Never<T>()
}

class ConditionAlwaysSerializer<T>(val inner: KSerializer<T>) :
    WrappingSerializer<Condition.Always<T>, Boolean>("Always") {
    override fun getDeferred(): KSerializer<Boolean> = Boolean.serializer()
    override fun inner(it: Condition.Always<T>): Boolean = true
    override fun outer(it: Boolean) = Condition.Always<T>()
}

class ConditionAndSerializer<T>(val inner: KSerializer<T>) :
    WrappingSerializer<Condition.And<T>, List<Condition<T>>>("And") {
    override fun getDeferred(): KSerializer<List<Condition<T>>> = ListSerializer(Condition.serializer(inner))
    override fun inner(it: Condition.And<T>): List<Condition<T>> = it.conditions
    override fun outer(it: List<Condition<T>>): Condition.And<T> = Condition.And(it)
}

class ConditionOrSerializer<T>(val inner: KSerializer<T>) :
    WrappingSerializer<Condition.Or<T>, List<Condition<T>>>("Or") {
    override fun getDeferred(): KSerializer<List<Condition<T>>> = ListSerializer(Condition.serializer(inner))
    override fun inner(it: Condition.Or<T>): List<Condition<T>> = it.conditions
    override fun outer(it: List<Condition<T>>): Condition.Or<T> = Condition.Or(it)
}

class ConditionNotSerializer<T>(val inner: KSerializer<T>) : WrappingSerializer<Condition.Not<T>, Condition<T>>("Not") {
    override fun getDeferred(): KSerializer<Condition<T>> = Condition.serializer(inner)
    override fun inner(it: Condition.Not<T>): Condition<T> = it.condition
    override fun outer(it: Condition<T>): Condition.Not<T> = Condition.Not(it)
}

class ConditionEqualSerializer<T : IsEquatable>(val inner: KSerializer<T>) :
    WrappingSerializer<Condition.Equal<T>, T>("Equal") {
    override fun getDeferred(): KSerializer<T> = inner
    override fun inner(it: Condition.Equal<T>): T = it.value
    override fun outer(it: T): Condition.Equal<T> = Condition.Equal(it)
}

class ConditionNotEqualSerializer<T : IsEquatable>(val inner: KSerializer<T>) :
    WrappingSerializer<Condition.NotEqual<T>, T>("NotEqual") {
    override fun getDeferred(): KSerializer<T> = inner
    override fun inner(it: Condition.NotEqual<T>): T = it.value
    override fun outer(it: T): Condition.NotEqual<T> = Condition.NotEqual(it)
}

class ConditionInsideSerializer<T : IsEquatable>(val inner: KSerializer<T>) :
    WrappingSerializer<Condition.Inside<T>, List<T>>("Inside") {
    override fun getDeferred() = ListSerializer(inner)
    override fun inner(it: Condition.Inside<T>): List<T> = it.values
    override fun outer(it: List<T>) = Condition.Inside(it)
}

class ConditionNotInsideSerializer<T : IsEquatable>(val inner: KSerializer<T>) :
    WrappingSerializer<Condition.NotInside<T>, List<T>>("NotInside") {
    override fun getDeferred() = ListSerializer(inner)
    override fun inner(it: Condition.NotInside<T>): List<T> = it.values
    override fun outer(it: List<T>) = Condition.NotInside(it)
}

class ConditionGreaterThanSerializer<T : Comparable<T>>(val inner: KSerializer<T>) :
    WrappingSerializer<Condition.GreaterThan<T>, T>("GreaterThan") {
    override fun getDeferred() = inner
    override fun inner(it: Condition.GreaterThan<T>): T = it.value
    override fun outer(it: T) = Condition.GreaterThan(it)
}

class ConditionLessThanSerializer<T : Comparable<T>>(val inner: KSerializer<T>) :
    WrappingSerializer<Condition.LessThan<T>, T>("LessThan") {
    override fun getDeferred() = inner
    override fun inner(it: Condition.LessThan<T>): T = it.value
    override fun outer(it: T) = Condition.LessThan(it)
}

class ConditionGreaterThanOrEqualSerializer<T : Comparable<T>>(val inner: KSerializer<T>) :
    WrappingSerializer<Condition.GreaterThanOrEqual<T>, T>("GreaterThanOrEqual") {
    override fun getDeferred() = inner
    override fun inner(it: Condition.GreaterThanOrEqual<T>): T = it.value
    override fun outer(it: T) = Condition.GreaterThanOrEqual(it)
}

class ConditionLessThanOrEqualSerializer<T : Comparable<T>>(val inner: KSerializer<T>) :
    WrappingSerializer<Condition.LessThanOrEqual<T>, T>("LessThanOrEqual") {
    override fun getDeferred() = inner
    override fun inner(it: Condition.LessThanOrEqual<T>): T = it.value
    override fun outer(it: T) = Condition.LessThanOrEqual(it)
}

object ConditionIntBitsClearSerializer : WrappingSerializer<Condition.IntBitsClear, Int>("IntBitsClear") {
    override fun getDeferred() = Int.serializer()
    override fun inner(it: Condition.IntBitsClear): Int = it.mask
    override fun outer(it: Int): Condition.IntBitsClear = Condition.IntBitsClear(it)
}

object ConditionIntBitsSetSerializer : WrappingSerializer<Condition.IntBitsSet, Int>("IntBitsSet") {
    override fun getDeferred() = Int.serializer()
    override fun inner(it: Condition.IntBitsSet): Int = it.mask
    override fun outer(it: Int): Condition.IntBitsSet = Condition.IntBitsSet(it)
}

object ConditionIntBitsAnyClearSerializer : WrappingSerializer<Condition.IntBitsAnyClear, Int>("IntBitsAnyClear") {
    override fun getDeferred() = Int.serializer()
    override fun inner(it: Condition.IntBitsAnyClear): Int = it.mask
    override fun outer(it: Int): Condition.IntBitsAnyClear = Condition.IntBitsAnyClear(it)
}

object ConditionIntBitsAnySetSerializer : WrappingSerializer<Condition.IntBitsAnySet, Int>("IntBitsAnySet") {
    override fun getDeferred() = Int.serializer()
    override fun inner(it: Condition.IntBitsAnySet): Int = it.mask
    override fun outer(it: Int): Condition.IntBitsAnySet = Condition.IntBitsAnySet(it)
}

class ConditionListAllElementsSerializer<T>(val inner: KSerializer<T>) :
    WrappingSerializer<Condition.ListAllElements<T>, Condition<T>>("ListAllElements") {
    override fun getDeferred(): KSerializer<Condition<T>> = Condition.serializer(inner)
    override fun inner(it: Condition.ListAllElements<T>): Condition<T> = it.condition
    override fun outer(it: Condition<T>): Condition.ListAllElements<T> = Condition.ListAllElements(it)
}

class ConditionListAnyElementsSerializer<T>(val inner: KSerializer<T>) :
    WrappingSerializer<Condition.ListAnyElements<T>, Condition<T>>("ListAnyElements") {
    override fun getDeferred(): KSerializer<Condition<T>> = Condition.serializer(inner)
    override fun inner(it: Condition.ListAnyElements<T>): Condition<T> = it.condition
    override fun outer(it: Condition<T>): Condition.ListAnyElements<T> = Condition.ListAnyElements(it)
}

class ConditionListSizesEqualsSerializer<T>(val inner: KSerializer<T>) :
    WrappingSerializer<Condition.ListSizesEquals<T>, Int>("ListSizesEquals") {
    override fun getDeferred() = Int.serializer()
    override fun inner(it: Condition.ListSizesEquals<T>): Int = it.count
    override fun outer(it: Int): Condition.ListSizesEquals<T> = Condition.ListSizesEquals(it)
}

class ConditionSetAllElementsSerializer<T>(val inner: KSerializer<T>) :
    WrappingSerializer<Condition.SetAllElements<T>, Condition<T>>("SetAllElements") {
    override fun getDeferred(): KSerializer<Condition<T>> = Condition.serializer(inner)
    override fun inner(it: Condition.SetAllElements<T>): Condition<T> = it.condition
    override fun outer(it: Condition<T>): Condition.SetAllElements<T> = Condition.SetAllElements(it)
}

class ConditionSetAnyElementsSerializer<T>(val inner: KSerializer<T>) :
    WrappingSerializer<Condition.SetAnyElements<T>, Condition<T>>("SetAnyElements") {
    override fun getDeferred(): KSerializer<Condition<T>> = Condition.serializer(inner)
    override fun inner(it: Condition.SetAnyElements<T>): Condition<T> = it.condition
    override fun outer(it: Condition<T>): Condition.SetAnyElements<T> = Condition.SetAnyElements(it)
}

class ConditionSetSizesEqualsSerializer<T>(val inner: KSerializer<T>) :
    WrappingSerializer<Condition.SetSizesEquals<T>, Int>("SetSizesEquals") {
    override fun getDeferred() = Int.serializer()
    override fun inner(it: Condition.SetSizesEquals<T>): Int = it.count
    override fun outer(it: Int): Condition.SetSizesEquals<T> = Condition.SetSizesEquals(it)
}

class ConditionExistsSerializer<T>(val inner: KSerializer<T>) :
    WrappingSerializer<Condition.Exists<T>, String>("Exists") {
    override fun getDeferred(): KSerializer<String> = serializer<String>()
    override fun inner(it: Condition.Exists<T>): String = it.key
    override fun outer(it: String): Condition.Exists<T> = Condition.Exists(it)
}

class ConditionIfNotNullSerializer<T>(val inner: KSerializer<T>) :
    WrappingSerializer<Condition.IfNotNull<T>, Condition<T>>("IfNotNull") {
    override fun getDeferred(): KSerializer<Condition<T>> = Condition.serializer(inner)
    override fun inner(it: Condition.IfNotNull<T>): Condition<T> = it.condition
    override fun outer(it: Condition<T>): Condition.IfNotNull<T> = Condition.IfNotNull(it)
}