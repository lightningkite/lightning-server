@file:SharedCode

package com.lightningkite.ktordb

import com.lightningkite.khrysalis.*
import kotlinx.serialization.*

@Serializable(ConditionSerializer::class)
// Why is this class open instead of sealed?  See https://github.com/Kotlin/kotlinx.serialization/issues/1843
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
    @SerialName("Search")
    data class Search(val value: String, val ignoreCase: Boolean): Condition<String>() { override fun invoke(on: String): Boolean = on.contains(value, ignoreCase) }

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

    data class OnField<K: IsCodableAndHashable, V: IsCodableAndHashable>(val key: DataClassProperty<in K, V>, val condition: Condition<V>): Condition<K>() { override fun invoke(on: K): Boolean = condition(key.get(on)) }

    @Serializable(ConditionIfNotNullSerializer::class)
    @SerialName("IfNotNull")
    data class IfNotNull<T: IsCodableAndHashable>(val condition: Condition<T>): Condition<T?>() { override fun invoke(on: T?): Boolean = on != null && condition(on) }
}