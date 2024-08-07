

package com.lightningkite.lightningdb

import com.lightningkite.*
import com.lightningkite.khrysalis.*
import com.lightningkite.GeoCoordinate
import kotlinx.serialization.*
import com.lightningkite.lightningdb.SerializableProperty

@Serializable(ConditionSerializer::class)
sealed class Condition<T> {
    open override fun hashCode(): Int = throw NotImplementedError()
    open override fun equals(other: Any?): Boolean = throw NotImplementedError()
    open operator fun invoke(on: T): Boolean = throw NotImplementedError()

    infix fun and(other: Condition<T>): Condition.And<T> = Condition.And(listOf(this, other))
    infix fun or(other: Condition<T>): Condition.Or<T> = Condition.Or(listOf(this, other))
    operator fun not(): Condition.Not<T> = Condition.Not(this)

    @Serializable(ConditionNeverSerializer::class)
    @SerialName("Never")
    class Never<T> : Condition<T>() {
        override fun invoke(on: T): Boolean = false
        override fun hashCode(): Int = 0

        @Suppress("UNCHECKED_CAST")
        override fun equals(other: Any?): Boolean = (other as? Condition.Never<T>) != null
    }

    @Serializable(ConditionAlwaysSerializer::class)
    @SerialName("Always")
    class Always<T> : Condition<T>() {
        override fun invoke(on: T): Boolean = true
        override fun hashCode(): Int = 1

        @Suppress("UNCHECKED_CAST")
        override fun equals(other: Any?): Boolean = (other as? Condition.Always<T>) != null
    }

    @Serializable(ConditionAndSerializer::class)
    @SerialName("And")
    data class And<T>(val conditions: List<Condition<T>>) : Condition<T>() {
        override fun invoke(on: T): Boolean = conditions.all { it(on) }
    }

    @Serializable(ConditionOrSerializer::class)
    @SerialName("Or")
    data class Or<T>(val conditions: List<Condition<T>>) : Condition<T>() {
        override fun invoke(on: T): Boolean = conditions.any { it(on) }
    }

    @Serializable(ConditionNotSerializer::class)
    @SerialName("Not")
    data class Not<T>(val condition: Condition<T>) : Condition<T>() {
        override fun invoke(on: T): Boolean = !condition(on)
    }

    @Serializable(ConditionEqualSerializer::class)
    @SerialName("Equal")
    data class Equal<T>(val value: T) : Condition<T>() {
        override fun invoke(on: T): Boolean = on == value
    }

    @Serializable(ConditionNotEqualSerializer::class)
    @SerialName("NotEqual")
    data class NotEqual<T>(val value: T) : Condition<T>() {
        override fun invoke(on: T): Boolean = on != value
    }

    @Serializable(ConditionInsideSerializer::class)
    @SerialName("Inside")
    data class Inside<T>(val values: List<T>) : Condition<T>() {
        override fun invoke(on: T): Boolean = values.contains(on)
    }

    @Serializable(ConditionNotInsideSerializer::class)
    @SerialName("NotInside")
    data class NotInside<T>(val values: List<T>) : Condition<T>() {
        override fun invoke(on: T): Boolean = !values.contains(on)
    }

    @Serializable(ConditionGreaterThanSerializer::class)
    @SerialName("GreaterThan")
    data class GreaterThan<T: Comparable<T>>(val value: T) : Condition<T>() {
        override fun invoke(on: T): Boolean = on > value
    }

    @Serializable(ConditionLessThanSerializer::class)
    @SerialName("LessThan")
    data class LessThan<T: Comparable<T>>(val value: T) : Condition<T>() {
        override fun invoke(on: T): Boolean = on < value
    }

    @Serializable(ConditionGreaterThanOrEqualSerializer::class)
    @SerialName("GreaterThanOrEqual")
    data class GreaterThanOrEqual<T: Comparable<T>>(val value: T) : Condition<T>() {
        override fun invoke(on: T): Boolean = on >= value
    }

    @Serializable(ConditionLessThanOrEqualSerializer::class)
    @SerialName("LessThanOrEqual")
    data class LessThanOrEqual<T: Comparable<T>>(val value: T) : Condition<T>() {
        override fun invoke(on: T): Boolean = on <= value
    }

    @Serializable
    @SerialName("StringContains")
    data class StringContains(val value: String, val ignoreCase: Boolean = false) : Condition<String>() {
        override fun invoke(on: String): Boolean = on.contains(value, ignoreCase)
    }

    @Serializable
    @SerialName("StringContains")
    data class RawStringContains<T: IsRawString>(val value: String, val ignoreCase: Boolean = false) : Condition<T>() {
        override fun invoke(on: T): Boolean = on.raw.contains(value, ignoreCase)
    }

    @Serializable
    @SerialName("GeoDistance")
    data class GeoDistance(val value: GeoCoordinate, val greaterThanKilometers: Double = 0.0, val lessThanKilometers: Double = 100_000.0) : Condition<GeoCoordinate>() {
        override fun invoke(on: GeoCoordinate): Boolean = on.distanceToKilometers(value) in greaterThanKilometers..lessThanKilometers
    }

    @Serializable
    @SerialName("FullTextSearch")
    data class FullTextSearch<T>(val value: String, val ignoreCase: Boolean = false) :
        Condition<T>() {
        override fun invoke(on: T): Boolean {
            val asText = on.toString()
            return value.split(' ').all { asText.contains(it, ignoreCase) }
        }
    }

    @Serializable
    @SerialName("RegexMatches")
    data class RegexMatches(val pattern: String, val ignoreCase: Boolean = false) : Condition<String>() {
        @Transient
        val regex = Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else setOf())
        override fun invoke(on: String): Boolean = regex.matches(on)
    }

    @Serializable(ConditionIntBitsClearSerializer::class)
    @SerialName("IntBitsClear")
    data class IntBitsClear(val mask: Int) : Condition<Int>() {
        override fun invoke(on: Int): Boolean = on and mask == 0
    }

    @Serializable(ConditionIntBitsSetSerializer::class)
    @SerialName("IntBitsSet")
    data class IntBitsSet(val mask: Int) : Condition<Int>() {
        override fun invoke(on: Int): Boolean = on and mask == mask
    }

    @Serializable(ConditionIntBitsAnyClearSerializer::class)
    @SerialName("IntBitsAnyClear")
    data class IntBitsAnyClear(val mask: Int) : Condition<Int>() {
        override fun invoke(on: Int): Boolean = on and mask < mask
    }

    @Serializable(ConditionIntBitsAnySetSerializer::class)
    @SerialName("IntBitsAnySet")
    data class IntBitsAnySet(val mask: Int) : Condition<Int>() {
        override fun invoke(on: Int): Boolean = on and mask > 0
    }

    // TODO: Merge collection operations once Khrysalis is fully deprecated
    @Serializable(ConditionListAllElementsSerializer::class)
    @SerialName("ListAllElements")
    data class ListAllElements<E>(val condition: Condition<E>) : Condition<List<E>>() {
        override fun invoke(on: List<E>): Boolean = on.all { condition(it) }
    }

    @Serializable(ConditionListAnyElementsSerializer::class)
    @SerialName("ListAnyElements")
    data class ListAnyElements<E>(val condition: Condition<E>) : Condition<List<E>>() {
        override fun invoke(on: List<E>): Boolean = on.any { condition(it) }
    }

    // TODO: Change to empty check
    @Serializable(ConditionListSizesEqualsSerializer::class)
    @SerialName("ListSizesEquals")
    data class ListSizesEquals<E>(val count: Int) : Condition<List<E>>() {
        override fun invoke(on: List<E>): Boolean = on.size == count
    }

    @Serializable(ConditionSetAllElementsSerializer::class)
    @SerialName("SetAllElements")
    data class SetAllElements<E>(val condition: Condition<E>) : Condition<Set<E>>() {
        override fun invoke(on: Set<E>): Boolean = on.all { condition(it) }
    }

    @Serializable(ConditionSetAnyElementsSerializer::class)
    @SerialName("SetAnyElements")
    data class SetAnyElements<E>(val condition: Condition<E>) : Condition<Set<E>>() {
        override fun invoke(on: Set<E>): Boolean = on.any { condition(it) }
    }

    // TODO: Change to empty check
    @Serializable(ConditionSetSizesEqualsSerializer::class)
    @SerialName("SetSizesEquals")
    data class SetSizesEquals<E>(val count: Int) : Condition<Set<E>>() {
        override fun invoke(on: Set<E>): Boolean = on.size == count
    }

    // TODO: Allow alternate keys once Khrysalis is fully deprecated
    @Serializable(ConditionExistsSerializer::class)
    @SerialName("Exists")
    data class Exists<V>(val key: String) : Condition<Map<String, V>>() {
        override fun invoke(on: Map<String, V>): Boolean = on.containsKey(key)
    }

    @Serializable
    @SerialName("OnKey")
    data class OnKey<V>(val key: String, val condition: Condition<V>) :
        Condition<Map<String, V>>() {
        @Suppress("UNCHECKED_CAST")
        override fun invoke(on: Map<String, V>): Boolean = on.containsKey(key) && condition(on[key] as V)
    }

    data class OnField<K, V>(
        val key: SerializableProperty<in K, V>,
        val condition: Condition<V>,
    ) : Condition<K>() {
        override fun invoke(on: K): Boolean = condition(key.get(on))
    }

    @Serializable(ConditionIfNotNullSerializer::class)
    @SerialName("IfNotNull")
    data class IfNotNull<T>(val condition: Condition<T>) : Condition<T?>() {
        override fun invoke(on: T?): Boolean = on != null && condition(on)
    }
}
