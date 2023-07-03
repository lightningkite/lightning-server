@file:OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)

package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.IsEquatable
import com.lightningkite.khrysalis.fatalError
import kotlinx.serialization.*
import kotlin.reflect.KClass
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
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

fun <K> KSerializer<K>.fieldSerializer(fieldName: String): KSerializer<*>? {
    val index = this.descriptor.elementNames.indexOf(fieldName)
    return (this as? GeneratedSerializer<*>)?.childSerializers()?.get(index)
}

private val serializers = HashMap<KSerializer<*>, MySealedClassSerializerInterface<*>>()

@Suppress("UNCHECKED_CAST")
private fun <Inner> getCond(inner: KSerializer<Inner>): MySealedClassSerializerInterface<Condition<Inner>> = serializers.getOrPut(inner) {
    MySealedClassSerializer(
        "com.lightningkite.lightningdb.Condition",
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

            if(inner.descriptor.kind is PrimitiveKind) {
                register(Condition.GreaterThan.serializer(inner))
                register(Condition.LessThan.serializer(inner))
                register(Condition.GreaterThanOrEqual.serializer(inner))
                register(Condition.LessThanOrEqual.serializer(inner))
            }

            register(Condition.FullTextSearch.serializer(inner))
            if (inner == String.serializer()) {
                register(Condition.StringContains.serializer())
                register(Condition.RegexMatches.serializer())
            }
            if (inner == Int.serializer()) {
                register(Condition.IntBitsClear.serializer())
                register(Condition.IntBitsSet.serializer())
                register(Condition.IntBitsAnyClear.serializer())
                register(Condition.IntBitsAnySet.serializer())
            }
            if (inner.descriptor.kind == StructureKind.LIST) {
                inner.listElement()?.let { element ->
                    register(Condition.ListAllElements.serializer(element))
                    register(Condition.ListAnyElements.serializer(element))
                    register(Condition.ListSizesEquals.serializer(element))
                    register(Condition.SetAllElements.serializer(element))
                    register(Condition.SetAnyElements.serializer(element))
                    register(Condition.SetSizesEquals.serializer(element))
                }
            }
            if (inner.descriptor.kind == StructureKind.MAP) {
                inner.mapValueElement()?.let { element ->
                    register(Condition.Exists.serializer(element))
                    register(Condition.OnKey.serializer(element))
                }
            }
            if (inner is GeneratedSerializer<*> && inner.descriptor.kind == StructureKind.CLASS && inner !is MySealedClassSerializerInterface) {
                val childSerializers = inner.childSerializers()
                val fields = try {inner.attemptGrabFields() } catch(e: Exception) { throw Exception("Failed while getting inner fields from ${inner}", e)}
                for (index in 0 until inner.descriptor.elementsCount) {
                    val name = inner.descriptor.getElementName(index)
                    val prop = fields[name]!!
                    register(
                        OnFieldSerializer<Any, Any?>(
                            prop as KProperty1<Any, Any?>,
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
        },
        alternateReadNames = mapOf(
            "Search" to "StringContains",
            "AllElements" to "ListAllElements",
            "AnyElements" to "ListAnyElements",
            "SizesEquals" to "ListSizesEquals",
        ),
        annotations = Condition::class.annotations
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
            is Condition.StringContains -> "StringContains"
            is Condition.RegexMatches -> "RegexMatches"
            is Condition.FullTextSearch -> "FullTextSearch"
            is Condition.IntBitsClear -> "IntBitsClear"
            is Condition.IntBitsSet -> "IntBitsSet"
            is Condition.IntBitsAnyClear -> "IntBitsAnyClear"
            is Condition.IntBitsAnySet -> "IntBitsAnySet"
            is Condition.ListAllElements<*> -> "ListAllElements"
            is Condition.ListAnyElements<*> -> "ListAnyElements"
            is Condition.ListSizesEquals<*> -> "ListSizesEquals"
            is Condition.SetAllElements<*> -> "SetAllElements"
            is Condition.SetAnyElements<*> -> "SetAnyElements"
            is Condition.SetSizesEquals<*> -> "SetSizesEquals"
            is Condition.Exists<*> -> "Exists"
            is Condition.OnKey<*> -> "OnKey"
            is Condition.IfNotNull<*> -> "IfNotNull"
            is Condition.OnField<*, *> -> it.key.name
            else -> fatalError()
        }
    }
} as MySealedClassSerializerInterface<Condition<Inner>>

class ConditionSerializer<Inner>(val inner: KSerializer<Inner>) : MySealedClassSerializerInterface<Condition<Inner>> by getCond(inner)

class OnFieldSerializer<K : Any, V>(
    val field: KProperty1<K, V>,
    val conditionSerializer: KSerializer<Condition<V>>
) : WrappingSerializer<Condition.OnField<K, V>, Condition<V>>(field.name) {
    override fun getDeferred(): KSerializer<Condition<V>> = conditionSerializer
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


class ConditionNeverSerializer<T>(val inner: KSerializer<T>) : WrappingSerializer<Condition.Never<T>, Boolean>("Never") {
    override fun getDeferred(): KSerializer<Boolean> = Boolean.serializer()
    override fun inner(it: Condition.Never<T>): Boolean = true
    override fun outer(it: Boolean) = Condition.Never<T>()
}

class ConditionAlwaysSerializer<T>(val inner: KSerializer<T>) : WrappingSerializer<Condition.Always<T>, Boolean>("Always") {
    override fun getDeferred(): KSerializer<Boolean> = Boolean.serializer()
    override fun inner(it: Condition.Always<T>): Boolean = true
    override fun outer(it: Boolean) = Condition.Always<T>()
}

class ConditionAndSerializer<T>(val inner: KSerializer<T>): WrappingSerializer<Condition.And<T>, List<Condition<T>>>("And") {
    override fun getDeferred(): KSerializer<List<Condition<T>>> = ListSerializer(Condition.serializer(inner))
    override fun inner(it: Condition.And<T>): List<Condition<T>> = it.conditions
    override fun outer(it: List<Condition<T>>): Condition.And<T> = Condition.And(it)
}

class ConditionOrSerializer<T>(val inner: KSerializer<T>): WrappingSerializer<Condition.Or<T>, List<Condition<T>>>("Or") {
    override fun getDeferred(): KSerializer<List<Condition<T>>> = ListSerializer(Condition.serializer(inner))
    override fun inner(it: Condition.Or<T>): List<Condition<T>> = it.conditions
    override fun outer(it: List<Condition<T>>): Condition.Or<T> = Condition.Or(it)
}

class ConditionNotSerializer<T>(val inner: KSerializer<T>) : WrappingSerializer<Condition.Not<T>, Condition<T>>("Not") {
    override fun getDeferred(): KSerializer<Condition<T>> = Condition.serializer(inner)
    override fun inner(it: Condition.Not<T>): Condition<T> = it.condition
    override fun outer(it: Condition<T>): Condition.Not<T> = Condition.Not(it)
}

class ConditionEqualSerializer<T : IsEquatable>(val inner: KSerializer<T>) : WrappingSerializer<Condition.Equal<T>, T>("Equal") {
    override fun getDeferred(): KSerializer<T> = inner
    override fun inner(it: Condition.Equal<T>): T = it.value
    override fun outer(it: T): Condition.Equal<T> = Condition.Equal(it)
}

class ConditionNotEqualSerializer<T : IsEquatable>(val inner: KSerializer<T>) : WrappingSerializer<Condition.NotEqual<T>, T>("NotEqual") {
    override fun getDeferred(): KSerializer<T> = inner
    override fun inner(it: Condition.NotEqual<T>): T = it.value
    override fun outer(it: T): Condition.NotEqual<T> = Condition.NotEqual(it)
}

class ConditionInsideSerializer<T : IsEquatable>(val inner: KSerializer<T>) : WrappingSerializer<Condition.Inside<T>, List<T>>("Inside") {
    override fun getDeferred() = ListSerializer(inner)
    override fun inner(it: Condition.Inside<T>): List<T> = it.values
    override fun outer(it: List<T>) = Condition.Inside(it)
}

class ConditionNotInsideSerializer<T : IsEquatable>(val inner: KSerializer<T>) : WrappingSerializer<Condition.NotInside<T>, List<T>>("NotInside") {
    override fun getDeferred() = ListSerializer(inner)
    override fun inner(it: Condition.NotInside<T>): List<T> = it.values
    override fun outer(it: List<T>) = Condition.NotInside(it)
}

class ConditionGreaterThanSerializer<T : Comparable<T>>(val inner: KSerializer<T>) : WrappingSerializer<Condition.GreaterThan<T>, T>("GreaterThan") {
    override fun getDeferred() = inner
    override fun inner(it: Condition.GreaterThan<T>): T = it.value
    override fun outer(it: T) = Condition.GreaterThan(it)
}

class ConditionLessThanSerializer<T : Comparable<T>>(val inner: KSerializer<T>) : WrappingSerializer<Condition.LessThan<T>, T>("LessThan") {
    override fun getDeferred() = inner
    override fun inner(it: Condition.LessThan<T>): T = it.value
    override fun outer(it: T) = Condition.LessThan(it)
}

class ConditionGreaterThanOrEqualSerializer<T : Comparable<T>>(val inner: KSerializer<T>) : WrappingSerializer<Condition.GreaterThanOrEqual<T>, T>("GreaterThanOrEqual") {
    override fun getDeferred() = inner
    override fun inner(it: Condition.GreaterThanOrEqual<T>): T = it.value
    override fun outer(it: T) = Condition.GreaterThanOrEqual(it)
}

class ConditionLessThanOrEqualSerializer<T : Comparable<T>>(val inner: KSerializer<T>) : WrappingSerializer<Condition.LessThanOrEqual<T>, T>("LessThanOrEqual") {
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

class ConditionListAllElementsSerializer<T>(val inner: KSerializer<T>) : WrappingSerializer<Condition.ListAllElements<T>, Condition<T>>("ListAllElements") {
    override fun getDeferred(): KSerializer<Condition<T>> = Condition.serializer(inner)
    override fun inner(it: Condition.ListAllElements<T>): Condition<T> = it.condition
    override fun outer(it: Condition<T>): Condition.ListAllElements<T> = Condition.ListAllElements(it)
}

class ConditionListAnyElementsSerializer<T>(val inner: KSerializer<T>) : WrappingSerializer<Condition.ListAnyElements<T>, Condition<T>>("ListAnyElements") {
    override fun getDeferred(): KSerializer<Condition<T>> = Condition.serializer(inner)
    override fun inner(it: Condition.ListAnyElements<T>): Condition<T> = it.condition
    override fun outer(it: Condition<T>): Condition.ListAnyElements<T> = Condition.ListAnyElements(it)
}

class ConditionListSizesEqualsSerializer<T>(val inner: KSerializer<T>) : WrappingSerializer<Condition.ListSizesEquals<T>, Int>("ListSizesEquals") {
    override fun getDeferred() = Int.serializer()
    override fun inner(it: Condition.ListSizesEquals<T>): Int = it.count
    override fun outer(it: Int): Condition.ListSizesEquals<T> = Condition.ListSizesEquals(it)
}

class ConditionSetAllElementsSerializer<T>(val inner: KSerializer<T>) : WrappingSerializer<Condition.SetAllElements<T>, Condition<T>>("SetAllElements") {
    override fun getDeferred(): KSerializer<Condition<T>> = Condition.serializer(inner)
    override fun inner(it: Condition.SetAllElements<T>): Condition<T> = it.condition
    override fun outer(it: Condition<T>): Condition.SetAllElements<T> = Condition.SetAllElements(it)
}

class ConditionSetAnyElementsSerializer<T>(val inner: KSerializer<T>) : WrappingSerializer<Condition.SetAnyElements<T>, Condition<T>>("SetAnyElements") {
    override fun getDeferred(): KSerializer<Condition<T>> = Condition.serializer(inner)
    override fun inner(it: Condition.SetAnyElements<T>): Condition<T> = it.condition
    override fun outer(it: Condition<T>): Condition.SetAnyElements<T> = Condition.SetAnyElements(it)
}

class ConditionSetSizesEqualsSerializer<T>(val inner: KSerializer<T>) : WrappingSerializer<Condition.SetSizesEquals<T>, Int>("SetSizesEquals") {
    override fun getDeferred() = Int.serializer()
    override fun inner(it: Condition.SetSizesEquals<T>): Int = it.count
    override fun outer(it: Int): Condition.SetSizesEquals<T> = Condition.SetSizesEquals(it)
}

class ConditionExistsSerializer<T>(val inner: KSerializer<T>) : WrappingSerializer<Condition.Exists<T>, String>("Exists") {
    override fun getDeferred(): KSerializer<String> = serializer<String>()
    override fun inner(it: Condition.Exists<T>): String = it.key
    override fun outer(it: String): Condition.Exists<T> = Condition.Exists(it)
}

class ConditionIfNotNullSerializer<T>(val inner: KSerializer<T>) : WrappingSerializer<Condition.IfNotNull<T>, Condition<T>>("IfNotNull") {
    override fun getDeferred(): KSerializer<Condition<T>> = Condition.serializer(inner)
    override fun inner(it: Condition.IfNotNull<T>): Condition<T> = it.condition
    override fun outer(it: Condition<T>): Condition.IfNotNull<T> = Condition.IfNotNull(it)
}