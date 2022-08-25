package com.lightningkite.lightningdb

import kotlinx.serialization.Serializable
import kotlin.reflect.KProperty1

@Serializable
data class Mask<T>(
    /**
     * If the condition does not pass, then the modification will be applied to mask the values.
     */
    val pairs: List<Pair<Condition<T>, Modification<T>>> = listOf()
) {
    operator fun invoke(on: T): T {
        var value = on
        for(pair in pairs) {
            if(!pair.first(on)) value = pair.second(on)
        }
        return value
    }
    private fun path(modification: Modification<*>): List<KProperty1<*, *>> {
        return when(modification) {
            is Modification.OnField<*, *> -> listOf(modification.key) + path(modification.modification)
            else -> listOf()
        }
    }
    fun permitSort(on: List<SortPart<T>>): Condition<T> {
        val totalConditions = ArrayList<Condition<T>>()
        for(pair in pairs) {
            val path = path(pair.second)
            if(on.any { it.field.property == path.firstOrNull() }) totalConditions.add(pair.first)
        }
        return when(totalConditions.size) {
            0 -> Condition.Always()
            1 -> totalConditions[0]
            else -> Condition.And(totalConditions)
        }
    }
    operator fun invoke(on: KProperty1<T, *>): Condition<T> {
        val totalConditions = ArrayList<Condition<T>>()
        for(pair in pairs) {
            val path = path(pair.second)
            if(on == path.firstOrNull()) totalConditions.add(pair.first)
        }
        return when(totalConditions.size) {
            0 -> Condition.Always()
            1 -> totalConditions[0]
            else -> Condition.And(totalConditions)
        }
    }
    class Builder<T>(
        val pairs: ArrayList<Pair<Condition<T>, Modification<T>>> = ArrayList()
    ) {
        val it = startChain<T>()
        infix fun <V> PropChain<T, V>.maskedTo(value: V) = this.assign(value)
        infix fun Modification<T>.unless(condition: Condition<T>) {
            pairs.add(condition to this)
        }
        fun always(modification: Modification<T>) {
            pairs.add(Condition.Never<T>() to modification)
        }
        fun build() = Mask(pairs)
    }
}

inline fun <T> mask(builder: Mask.Builder<T>.()->Unit): Mask<T> {
    return Mask.Builder<T>().apply(builder).build()
}
