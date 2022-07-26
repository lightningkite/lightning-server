@file:SharedCode

package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.*
import jdk.jshell.spi.ExecutionControl.NotImplementedException
import kotlinx.serialization.*
import kotlin.reflect.KProperty1

@Serializable(ConditionSerializer::class)
// Why is this class open instead of sealed?  See https://github.com/Kotlin/kotlinx.serialization/issues/1843
@Description("""
    Some kind of condition on a given type.
    Formatted as a JSON object with one of the following keys:
    <some property on the type> - A condition for that particular property.
    "Never" - Set to true, indicates that the condition never passes.
    "Always" - Set to true, indicates that the condition always passes.
    "And" - A list of other conditions, which all must pass.
    "Or" - A list of other conditions, any of which can pass.
    "Not" - Another condition to invert.
    "Equal" - Must be equal to the given value.
    "NotEqual" - Must not be equal to the given value.
    "Inside" - Must be inside the given list of values.
    "NotInside" - Must not be inside the given list of values.
    "GreaterThan" - Must be greater than the given value.
    "LessThan" - Must be less than the given value.
    "GreaterThanOrEqual" - Must be greater than or equal to the given value.
    "LessThanOrEqual" - Must be less than or equal to the given value.
    "StringContains" - TODO: Document
    "FullTextSearch" - TODO: Document
    "RegexMatches" - TODO: Document
    "IntBitsClear" - TODO: Document
    "IntBitsSet" - TODO: Document
    "IntBitsAnyClear" - TODO: Document
    "IntBitsAnySet" - TODO: Document
    "AllElements" - TODO: Document
    "AnyElements" - TODO: Document
    "SizesEquals" - TODO: Document
    "Exists" - TODO: Document
    "OnKey" - TODO: Document
    "IfNotNull" - TODO: Document
""")
open class Condition<T: IsCodableAndHashable> protected constructor()  {
    open override fun hashCode(): Int { fatalError() }
    open override fun equals(other: Any?): Boolean { fatalError() }

    open operator fun invoke(on: T): Boolean { fatalError() }
    open fun simplify(): Condition<T> = this

    infix fun and(other: Condition<T>): Condition.And<T> = Condition.And(listOf(this, other))
    infix fun or(other: Condition<T>): Condition.Or<T> = Condition.Or(listOf(this, other))
    operator fun not(): Condition.Not<T> = Condition.Not(this)

    @Serializable(ConditionNeverSerializer::class)
    @SerialName("Never")
    class Never<T: IsCodableAndHashable>: Condition<T>() {
        override fun invoke(on: T): Boolean = false
        override fun hashCode(): Int = 0
        @Suppress("UNCHECKED_CAST")
        override fun equals(other: Any?): Boolean = (other as? Condition.Never<T>) != null
    }

    @Serializable(ConditionAlwaysSerializer::class)
    @SerialName("Always")
    class Always<T: IsCodableAndHashable>: Condition<T>() {
        override fun invoke(on: T): Boolean = true
        override fun hashCode(): Int = 1
        @Suppress("UNCHECKED_CAST")
        override fun equals(other: Any?): Boolean = (other as? Condition.Always<T>) != null
    }

    @Serializable(ConditionAndSerializer::class)
    @SerialName("And")
    data class And<T: IsCodableAndHashable>(val conditions: List<Condition<T>>): Condition<T>() {
        override fun invoke(on: T): Boolean = conditions.all { it(on) }
        override fun simplify(): Condition<T> = if(conditions.isEmpty()) Condition.Always() else Condition.And(conditions.distinct())
    }

    @Serializable(ConditionOrSerializer::class)
    @SerialName("Or")
    data class Or<T: IsCodableAndHashable>(val conditions: List<Condition<T>>): Condition<T>() {
        override fun invoke(on: T): Boolean = conditions.any { it(on) }
        override fun simplify(): Condition<T> = if(conditions.isEmpty()) Condition.Never() else Condition.Or(conditions.distinct())
    }

    @Serializable(ConditionNotSerializer::class)
    @SerialName("Not")
    data class Not<T: IsCodableAndHashable>(val condition: Condition<T>): Condition<T>() {
        override fun invoke(on: T): Boolean = !condition(on)
        override fun simplify(): Condition<T> = (condition as? Condition.Not<T>)?.condition ?: this
    }

    @Serializable(ConditionEqualSerializer::class)
    @SerialName("Equal")
    data class Equal<T: IsCodableAndHashable>(val value: T): Condition<T>() { override fun invoke(on: T): Boolean = on == value }

    @Serializable(ConditionNotEqualSerializer::class)
    @SerialName("NotEqual")
    data class NotEqual<T: IsCodableAndHashable>(val value: T): Condition<T>() { override fun invoke(on: T): Boolean = on != value }

    @Serializable(ConditionInsideSerializer::class)
    @SerialName("Inside")
    data class Inside<T: IsCodableAndHashable>(val values: List<T>): Condition<T>() { override fun invoke(on: T): Boolean = values.contains(on) }

    @Serializable(ConditionNotInsideSerializer::class)
    @SerialName("NotInside")
    data class NotInside<T: IsCodableAndHashable>(val values: List<T>): Condition<T>() { override fun invoke(on: T): Boolean = !values.contains(on) }

    @Serializable(ConditionGreaterThanSerializer::class)
    @SerialName("GreaterThan")
    data class GreaterThan<T: ComparableCodableAndHashable<T>>(val value: T): Condition<T>() { override fun invoke(on: T): Boolean = on > value }

    @Serializable(ConditionLessThanSerializer::class)
    @SerialName("LessThan")
    data class LessThan<T: ComparableCodableAndHashable<T>>(val value: T): Condition<T>() { override fun invoke(on: T): Boolean = on < value }

    @Serializable(ConditionGreaterThanOrEqualSerializer::class)
    @SerialName("GreaterThanOrEqual")
    data class GreaterThanOrEqual<T: ComparableCodableAndHashable<T>>(val value: T): Condition<T>() { override fun invoke(on: T): Boolean = on >= value }

    @Serializable(ConditionLessThanOrEqualSerializer::class)
    @SerialName("LessThanOrEqual")
    data class LessThanOrEqual<T: ComparableCodableAndHashable<T>>(val value: T): Condition<T>() { override fun invoke(on: T): Boolean = on <= value }

    @Serializable
    @SerialName("StringContains")
    data class StringContains(val value: String, val ignoreCase: Boolean = false): Condition<String>() { override fun invoke(on: String): Boolean = on.contains(value, ignoreCase) }

    @Serializable
    @SerialName("FullTextSearch")
    data class FullTextSearch<T: IsCodableAndHashable>(val value: String, val ignoreCase: Boolean = false): Condition<T>() {
        override fun invoke(on: T): Boolean {
            fatalError("Not Implemented locally")
        }
    }

    @Serializable
    @SerialName("RegexMatches")
    data class RegexMatches(val pattern: String, val ignoreCase: Boolean = false): Condition<String>() {
        @Transient
        val regex = Regex(pattern, if(ignoreCase) setOf(RegexOption.IGNORE_CASE) else setOf())
        override fun invoke(on: String): Boolean = regex.matches(on)
    }

    @Serializable(ConditionIntBitsClearSerializer::class)
    @SerialName("IntBitsClear")
    data class IntBitsClear(val mask: Int): Condition<Int>() { override fun invoke(on: Int): Boolean = on and mask == 0 }

    @Serializable(ConditionIntBitsSetSerializer::class)
    @SerialName("IntBitsSet")
    data class IntBitsSet(val mask: Int): Condition<Int>() { override fun invoke(on: Int): Boolean = on and mask == mask }

    @Serializable(ConditionIntBitsAnyClearSerializer::class)
    @SerialName("IntBitsAnyClear")
    data class IntBitsAnyClear(val mask: Int): Condition<Int>() { override fun invoke(on: Int): Boolean = on and mask < mask }

    @Serializable(ConditionIntBitsAnySetSerializer::class)
    @SerialName("IntBitsAnySet")
    data class IntBitsAnySet(val mask: Int): Condition<Int>() { override fun invoke(on: Int): Boolean = on and mask > 0 }

    @Serializable(ConditionAllElementsSerializer::class)
    @SerialName("AllElements")
    data class AllElements<E: IsCodableAndHashable>(val condition: Condition<E>): Condition<List<E>>() { override fun invoke(on: List<E>): Boolean = on.all { condition(it) } }

    @Serializable(ConditionAnyElementsSerializer::class)
    @SerialName("AnyElements")
    data class AnyElements<E: IsCodableAndHashable>(val condition: Condition<E>): Condition<List<E>>() { override fun invoke(on: List<E>): Boolean = on.any { condition(it) } }

    @Serializable(ConditionSizesEqualsSerializer::class)
    @SerialName("SizesEquals")
    data class SizesEquals<E: IsCodableAndHashable>(val count: Int): Condition<List<E>>() { override fun invoke(on: List<E>): Boolean = on.size == count }

    @Serializable(ConditionExistsSerializer::class)
    @SerialName("Exists")
    data class Exists<V: IsCodableAndHashable>(val key: String): Condition<Map<String, V>>() { override fun invoke(on: Map<String, V>): Boolean = on.containsKey(key) }

    @Serializable
    @SerialName("OnKey")
    data class OnKey<V: IsCodableAndHashable>(val key: String, val condition: Condition<V>): Condition<Map<String, V>>() { override fun invoke(on: Map<String, V>): Boolean = on.containsKey(key) && condition(on[key] as V) }

    data class OnField<K: IsCodableAndHashable, V: IsCodableAndHashable>(val key: KProperty1<in K, V>, val condition: Condition<V>): Condition<K>() { override fun invoke(on: K): Boolean = condition(key.get(on)) }

    @Serializable(ConditionIfNotNullSerializer::class)
    @SerialName("IfNotNull")
    data class IfNotNull<T: IsCodableAndHashable>(val condition: Condition<T>): Condition<T?>() { override fun invoke(on: T?): Boolean = on != null && condition(on) }
}
