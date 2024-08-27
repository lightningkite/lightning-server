

package com.lightningkite.lightningdb

import com.lightningkite.GeoCoordinate
import kotlinx.serialization.*
import com.lightningkite.serialization.SerializableProperty
import kotlin.jvm.JvmName

@Serializable(ConditionSerializer::class)
sealed class Condition<in T> {
    open override fun hashCode(): Int = throw NotImplementedError()
    open override fun equals(other: Any?): Boolean = throw NotImplementedError()
    open operator fun invoke(on: T): Boolean = throw NotImplementedError()

    @SerialName("Never")
    @Serializable
    data object Never : Condition<Any?>() {
        @Deprecated("Just use it directly", ReplaceWith("Condition.Never")) inline operator fun invoke() = this
        @JvmName("invokeOp")
        @Deprecated("Just use it directly", ReplaceWith("Condition.Never")) inline operator fun <T> invoke() = this
        override fun invoke(on: Any?): Boolean = false
        override fun toString(): String = "false"
    }

    @SerialName("Always")
    @Serializable
    data object Always : Condition<Any?>() {
        @Deprecated("Just use it directly", ReplaceWith("Condition.Always")) inline operator fun invoke() = this
        @JvmName("invokeOp")
        @Deprecated("Just use it directly", ReplaceWith("Condition.Always")) inline operator fun <T> invoke() = this
        override fun invoke(on: Any?): Boolean = true
        override fun toString(): String = "true"
    }

    @Serializable(ConditionAndSerializer::class)
    @SerialName("And")
    data class And<T>(val conditions: List<Condition<T>>) : Condition<T>() {
        override fun invoke(on: T): Boolean = conditions.all { it(on) }
        override fun toString(): String = conditions.joinToString(" && ", "(", ")")
    }

    @Serializable(ConditionOrSerializer::class)
    @SerialName("Or")
    data class Or<T>(val conditions: List<Condition<T>>) : Condition<T>() {
        override fun invoke(on: T): Boolean = conditions.any { it(on) }
        override fun toString(): String = conditions.joinToString(" || ", "(", ")")
    }

    @Serializable(ConditionNotSerializer::class)
    @SerialName("Not")
    data class Not<T>(val condition: Condition<T>) : Condition<T>() {
        override fun invoke(on: T): Boolean = !condition(on)
        override fun toString(): String = "!($condition)"
    }

    @Serializable(ConditionEqualSerializer::class)
    @SerialName("Equal")
    data class Equal<T>(val value: T) : Condition<T>() {
        override fun invoke(on: T): Boolean = on == value
        override fun toString(): String = " == $value"
    }

    @Serializable(ConditionNotEqualSerializer::class)
    @SerialName("NotEqual")
    data class NotEqual<T>(val value: T) : Condition<T>() {
        override fun invoke(on: T): Boolean = on != value
        override fun toString(): String = " != $value"
    }

    @Serializable(ConditionInsideSerializer::class)
    @SerialName("Inside")
    data class Inside<T>(val values: List<T>) : Condition<T>() {
        override fun invoke(on: T): Boolean = values.contains(on)
        override fun toString(): String = " in $values"
    }

    @Serializable(ConditionNotInsideSerializer::class)
    @SerialName("NotInside")
    data class NotInside<T>(val values: List<T>) : Condition<T>() {
        override fun invoke(on: T): Boolean = !values.contains(on)
        override fun toString(): String = " !in $values"
    }

    @Serializable(ConditionGreaterThanSerializer::class)
    @SerialName("GreaterThan")
    data class GreaterThan<T: Comparable<T>>(val value: T) : Condition<T>() {
        override fun invoke(on: T): Boolean = on > value
        override fun toString(): String = " > $value"
    }

    @Serializable(ConditionLessThanSerializer::class)
    @SerialName("LessThan")
    data class LessThan<T: Comparable<T>>(val value: T) : Condition<T>() {
        override fun invoke(on: T): Boolean = on < value
        override fun toString(): String = " < $value"
    }

    @Serializable(ConditionGreaterThanOrEqualSerializer::class)
    @SerialName("GreaterThanOrEqual")
    data class GreaterThanOrEqual<T: Comparable<T>>(val value: T) : Condition<T>() {
        override fun invoke(on: T): Boolean = on >= value
        override fun toString(): String = " >= $value"
    }

    @Serializable(ConditionLessThanOrEqualSerializer::class)
    @SerialName("LessThanOrEqual")
    data class LessThanOrEqual<T: Comparable<T>>(val value: T) : Condition<T>() {
        override fun invoke(on: T): Boolean = on <= value
        override fun toString(): String = " <= $value"
    }

    @Serializable
    @SerialName("StringContains")
    data class StringContains(val value: String, val ignoreCase: Boolean = false) : Condition<String>() {
        override fun invoke(on: String): Boolean = on.contains(value, ignoreCase)
        override fun toString(): String = ".contains($value, $ignoreCase)"
    }

    @Serializable
    @SerialName("GeoDistance")
    data class GeoDistance(val value: GeoCoordinate, val greaterThanKilometers: Double = 0.0, val lessThanKilometers: Double = 100_000.0) : Condition<GeoCoordinate>() {
        override fun invoke(on: GeoCoordinate): Boolean = on.distanceToKilometers(value) in greaterThanKilometers..lessThanKilometers
        override fun toString(): String = ".distanceToKilometers(value) in $greaterThanKilometers..$lessThanKilometers"
    }

    @Serializable
    @SerialName("FullTextSearch")
    data class FullTextSearch<T>(val value: String, val ignoreCase: Boolean = false) :
        Condition<T>() {
        override fun invoke(on: T): Boolean {
            val asText = on.toString()
            return value.split(' ').all { asText.contains(it, ignoreCase) }
        }
        override fun toString(): String = ".fullTextSearch($value, $ignoreCase)"
    }

    @Serializable
    @SerialName("RegexMatches")
    data class RegexMatches(val pattern: String, val ignoreCase: Boolean = false) : Condition<String>() {
        @Transient
        val regex = Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else setOf())
        override fun invoke(on: String): Boolean = regex.matches(on)
        override fun toString(): String = ".contains(Regex($pattern), $ignoreCase)"
    }

    @Serializable(ConditionIntBitsClearSerializer::class)
    @SerialName("IntBitsClear")
    data class IntBitsClear(val mask: Int) : Condition<Int>() {
        override fun invoke(on: Int): Boolean = on and mask == 0
        override fun toString(): String = ".bitsClear(${mask.toString(16)})"
    }

    @Serializable(ConditionIntBitsSetSerializer::class)
    @SerialName("IntBitsSet")
    data class IntBitsSet(val mask: Int) : Condition<Int>() {
        override fun invoke(on: Int): Boolean = on and mask == mask
        override fun toString(): String = ".bitsSet(${mask.toString(16)})"
    }

    @Serializable(ConditionIntBitsAnyClearSerializer::class)
    @SerialName("IntBitsAnyClear")
    data class IntBitsAnyClear(val mask: Int) : Condition<Int>() {
        override fun invoke(on: Int): Boolean = on and mask < mask
        override fun toString(): String = ".bitsAnyClear(${mask.toString(16)})"
    }

    @Serializable(ConditionIntBitsAnySetSerializer::class)
    @SerialName("IntBitsAnySet")
    data class IntBitsAnySet(val mask: Int) : Condition<Int>() {
        override fun invoke(on: Int): Boolean = on and mask > 0
        override fun toString(): String = ".bitsAnySet(${mask.toString(16)})"
    }

    // TODO: Merge collection operations once Khrysalis is fully deprecated
    @Serializable(ConditionListAllElementsSerializer::class)
    @SerialName("ListAllElements")
    data class ListAllElements<E>(val condition: Condition<E>) : Condition<List<E>>() {
        override fun invoke(on: List<E>): Boolean = on.all { condition(it) }
        override fun toString(): String = ".all { it$condition }"
    }

    @Serializable(ConditionListAnyElementsSerializer::class)
    @SerialName("ListAnyElements")
    data class ListAnyElements<E>(val condition: Condition<E>) : Condition<List<E>>() {
        override fun invoke(on: List<E>): Boolean = on.any { condition(it) }
        override fun toString(): String = ".any { it$condition }"
    }

    // TODO: Change to empty check
    @Serializable(ConditionListSizesEqualsSerializer::class)
    @SerialName("ListSizesEquals")
    data class ListSizesEquals<E>(val count: Int) : Condition<List<E>>() {
        override fun invoke(on: List<E>): Boolean = on.size == count
        override fun toString(): String = ".size == $count"
    }

    @Serializable(ConditionSetAllElementsSerializer::class)
    @SerialName("SetAllElements")
    data class SetAllElements<E>(val condition: Condition<E>) : Condition<Set<E>>() {
        override fun invoke(on: Set<E>): Boolean = on.all { condition(it) }
        override fun toString(): String = ".all { it$condition }"
    }

    @Serializable(ConditionSetAnyElementsSerializer::class)
    @SerialName("SetAnyElements")
    data class SetAnyElements<E>(val condition: Condition<E>) : Condition<Set<E>>() {
        override fun invoke(on: Set<E>): Boolean = on.any { condition(it) }
        override fun toString(): String = ".any { it$condition }"
    }

    // TODO: Change to empty check
    @Serializable(ConditionSetSizesEqualsSerializer::class)
    @SerialName("SetSizesEquals")
    data class SetSizesEquals<E>(val count: Int) : Condition<Set<E>>() {
        override fun invoke(on: Set<E>): Boolean = on.size == count
        override fun toString(): String = ".size == $count"
    }

    // TODO: Allow alternate keys once Khrysalis is fully deprecated
    @Serializable(ConditionExistsSerializer::class)
    @SerialName("Exists")
    data class Exists<V>(val key: String) : Condition<Map<String, V>>() {
        override fun invoke(on: Map<String, V>): Boolean = on.containsKey(key)
        override fun toString(): String = ".containsKey($key)"
    }

    @Serializable
    @SerialName("OnKey")
    data class OnKey<V>(val key: String, val condition: Condition<V>) :
        Condition<Map<String, V>>() {
        @Suppress("UNCHECKED_CAST")
        override fun invoke(on: Map<String, V>): Boolean = on.containsKey(key) && condition(on[key] as V)
        override fun toString(): String = "[$key]$condition"
    }

    data class OnField<K, V>(
        val key: SerializableProperty<in K, V>,
        val condition: Condition<V>,
    ) : Condition<K>() {
        override fun invoke(on: K): Boolean = condition(key.get(on))
        override fun toString(): String {
            return if(condition is Condition.OnField<*, *>)
                "${key.name}.$condition"
            else
                "${key.name}$condition"
        }
    }

    @Serializable(ConditionIfNotNullSerializer::class)
    @SerialName("IfNotNull")
    data class IfNotNull<T>(val condition: Condition<T>) : Condition<T?>() {
        override fun invoke(on: T?): Boolean = on != null && condition(on)
        override fun toString(): String = " != null && $condition"
    }
}

infix fun <T> Condition<T>.and(other: Condition<T>): Condition.And<T> = Condition.And(listOf(this, other))
infix fun <T> Condition<T>.or(other: Condition<T>): Condition.Or<T> = Condition.Or(listOf(this, other))
operator fun <T> Condition<T>.not(): Condition.Not<T> = Condition.Not(this)
