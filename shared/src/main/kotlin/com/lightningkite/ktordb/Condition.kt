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
    @SerialName("com.lightningkite.ktordb.Condition.Never")
    class Never<T: IsCodableAndHashable>: Condition<T>() {
        override fun invoke(on: T): Boolean = false
        override fun hashCode(): Int = 0
        @Suppress("UNCHECKED_CAST")
        override fun equals(other: Any?): Boolean = (other as? Condition.Never<T>) != null
    }

    @Serializable(ConditionAlwaysSerializer::class)
    @SerialName("com.lightningkite.ktordb.Condition.Always")
    class Always<T: IsCodableAndHashable>: Condition<T>() {
        override fun invoke(on: T): Boolean = true
        override fun hashCode(): Int = 1
        @Suppress("UNCHECKED_CAST")
        override fun equals(other: Any?): Boolean = (other as? Condition.Always<T>) != null
    }

    @Serializable(ConditionAndSerializer::class)
    @SerialName("com.lightningkite.ktordb.Condition.And")
    data class And<T: IsCodableAndHashable>(val conditions: List<Condition<T>>): Condition<T>() {
        override fun invoke(on: T): Boolean = conditions.all { it(on) }
        override fun simplify(): Condition<T> = if(conditions.isEmpty()) Condition.Always() else Condition.And(conditions.distinct())
    }

    @Serializable(ConditionOrSerializer::class)
    @SerialName("com.lightningkite.ktordb.Condition.Or")
    data class Or<T: IsCodableAndHashable>(val conditions: List<Condition<T>>): Condition<T>() {
        override fun invoke(on: T): Boolean = conditions.any { it(on) }
        override fun simplify(): Condition<T> = if(conditions.isEmpty()) Condition.Never() else Condition.Or(conditions.distinct())
    }

    @Serializable(ConditionNotSerializer::class)
    @SerialName("com.lightningkite.ktordb.Condition.Not")
    data class Not<T: IsCodableAndHashable>(val condition: Condition<T>): Condition<T>() {
        override fun invoke(on: T): Boolean = !condition(on)
        override fun simplify(): Condition<T> = (condition as? Condition.Not<T>)?.condition ?: this
    }

    @Serializable(ConditionEqualSerializer::class)
    @SerialName("com.lightningkite.ktordb.Condition.Equal")
    data class Equal<T: IsCodableAndHashable>(val value: T): Condition<T>() { override fun invoke(on: T): Boolean = on == value }

    @Serializable(ConditionNotEqualSerializer::class)
    @SerialName("com.lightningkite.ktordb.Condition.NotEqual")
    data class NotEqual<T: IsCodableAndHashable>(val value: T): Condition<T>() { override fun invoke(on: T): Boolean = on != value }

    @Serializable(ConditionInsideSerializer::class)
    @SerialName("com.lightningkite.ktordb.Condition.Inside")
    data class Inside<T: IsCodableAndHashable>(val values: List<T>): Condition<T>() { override fun invoke(on: T): Boolean = values.contains(on) }

    @Serializable(ConditionNotInsideSerializer::class)
    @SerialName("com.lightningkite.ktordb.Condition.NotInside")
    data class NotInside<T: IsCodableAndHashable>(val values: List<T>): Condition<T>() { override fun invoke(on: T): Boolean = !values.contains(on) }

    @Serializable(ConditionGreaterThanSerializer::class)
    @SerialName("com.lightningkite.ktordb.Condition.GreaterThan")
    data class GreaterThan<T: ComparableCodableAndHashable<T>>(val value: T): Condition<T>() { override fun invoke(on: T): Boolean = on > value }

    @Serializable(ConditionLessThanSerializer::class)
    @SerialName("com.lightningkite.ktordb.Condition.LessThan")
    data class LessThan<T: ComparableCodableAndHashable<T>>(val value: T): Condition<T>() { override fun invoke(on: T): Boolean = on < value }

    @Serializable(ConditionGreaterThanOrEqualSerializer::class)
    @SerialName("com.lightningkite.ktordb.Condition.GreaterThanOrEqual")
    data class GreaterThanOrEqual<T: ComparableCodableAndHashable<T>>(val value: T): Condition<T>() { override fun invoke(on: T): Boolean = on >= value }

    @Serializable(ConditionLessThanOrEqualSerializer::class)
    @SerialName("com.lightningkite.ktordb.Condition.LessThanOrEqual")
    data class LessThanOrEqual<T: ComparableCodableAndHashable<T>>(val value: T): Condition<T>() { override fun invoke(on: T): Boolean = on <= value }

    @Serializable
    @SerialName("com.lightningkite.ktordb.Condition.Search")
    data class Search(val value: String, val ignoreCase: Boolean): Condition<String>() { override fun invoke(on: String): Boolean = on.contains(value, ignoreCase) }

    @Serializable(ConditionIntBitsClearSerializer::class)
    @SerialName("com.lightningkite.ktordb.Condition.IntBitsClear")
    data class IntBitsClear(val mask: Int): Condition<Int>() { override fun invoke(on: Int): Boolean = on and mask == 0 }

    @Serializable(ConditionIntBitsSetSerializer::class)
    @SerialName("com.lightningkite.ktordb.Condition.IntBitsSet")
    data class IntBitsSet(val mask: Int): Condition<Int>() { override fun invoke(on: Int): Boolean = on and mask == mask }

    @Serializable(ConditionIntBitsAnyClearSerializer::class)
    @SerialName("com.lightningkite.ktordb.Condition.IntBitsAnyClear")
    data class IntBitsAnyClear(val mask: Int): Condition<Int>() { override fun invoke(on: Int): Boolean = on and mask < mask }

    @Serializable(ConditionIntBitsAnySetSerializer::class)
    @SerialName("com.lightningkite.ktordb.Condition.IntBitsAnySet")
    data class IntBitsAnySet(val mask: Int): Condition<Int>() { override fun invoke(on: Int): Boolean = on and mask > 0 }

    @Serializable(ConditionAllElementsSerializer::class)
    @SerialName("com.lightningkite.ktordb.Condition.AllElements")
    data class AllElements<E: IsCodableAndHashable>(val condition: Condition<E>): Condition<List<E>>() { override fun invoke(on: List<E>): Boolean = on.all { condition(it) } }

    @Serializable(ConditionAnyElementsSerializer::class)
    @SerialName("com.lightningkite.ktordb.Condition.AnyElements")
    data class AnyElements<E: IsCodableAndHashable>(val condition: Condition<E>): Condition<List<E>>() { override fun invoke(on: List<E>): Boolean = on.any { condition(it) } }

    @Serializable(ConditionSizesEqualsSerializer::class)
    @SerialName("com.lightningkite.ktordb.Condition.SizesEquals")
    data class SizesEquals<E: IsCodableAndHashable>(val count: Int): Condition<List<E>>() { override fun invoke(on: List<E>): Boolean = on.size == count }

    @Serializable(ConditionExistsSerializer::class)
    @SerialName("com.lightningkite.ktordb.Condition.Exists")
    data class Exists<V: IsCodableAndHashable>(val key: String): Condition<Map<String, V>>() { override fun invoke(on: Map<String, V>): Boolean = on.containsKey(key) }

    @Serializable
    @SerialName("com.lightningkite.ktordb.Condition.OnKey")
    data class OnKey<V: IsCodableAndHashable>(val key: String, val condition: Condition<V>): Condition<Map<String, V>>() { override fun invoke(on: Map<String, V>): Boolean = on.containsKey(key) && condition(on[key] as V) }

    data class OnField<K: IsCodableAndHashable, V: IsCodableAndHashable>(val key: DataClassProperty<in K, V>, val condition: Condition<V>): Condition<K>() { override fun invoke(on: K): Boolean = condition(key.get(on)) }

    @Serializable(ConditionIfNotNullSerializer::class)
    @SerialName("com.lightningkite.ktordb.Condition.IfNotNull")
    data class IfNotNull<T: IsCodableAndHashable>(val condition: Condition<T>): Condition<T?>() { override fun invoke(on: T?): Boolean = on != null && condition(on) }
}
