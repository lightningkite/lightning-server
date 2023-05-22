package com.lightningkite.lightningdb

import kotlinx.serialization.Serializable
import kotlin.reflect.KProperty1

/**
 * Rules about how outgoing data should be masked to prevent unwanted information from leaking.
 */
@Serializable
data class Mask<T>(
    /**
     * If the condition does not pass, then the modification will be applied to mask the values.
     */
    val pairs: List<Pair<Condition<T>, Modification<T>>> = listOf()
) {
    /**
     * Masks a single instance.
     */
    operator fun invoke(on: T): T {
        var value = on
        for (pair in pairs) {
            if (!pair.first(on)) value = pair.second(value)
        }
        return value
    }

    /**
     * Check under what conditions a given sort should be permitted.
     */
    fun permitSort(on: List<SortPart<T>>): Condition<T> {
        val totalConditions = ArrayList<Condition<T>>()
        for (pair in pairs) {
            if (on.any { pair.second.matchesPath(it.field.property) }) totalConditions.add(pair.first)
        }
        return when (totalConditions.size) {
            0 -> Condition.Always()
            1 -> totalConditions[0]
            else -> Condition.And(totalConditions)
        }
    }

    /**
     * Check under what conditions a property should be accessible.
     */
    operator fun invoke(on: KProperty1<T, *>): Condition<T> {
        val totalConditions = ArrayList<Condition<T>>()
        for (pair in pairs) {
            if (pair.second.matchesPath(on)) totalConditions.add(pair.first)
        }
        return when (totalConditions.size) {
            0 -> Condition.Always()
            1 -> totalConditions[0]
            else -> Condition.And(totalConditions)
        }
    }

    /**
     * Adds additional restrictions to a condition to prevent a leak of information
     */
    operator fun invoke(condition: Condition<T>): Condition<T> {
        val totalConditions = ArrayList<Condition<T>>()
        for (pair in pairs) {
            if (condition.matchesPath(pair.second)) totalConditions.add(pair.first)
        }
        return when (totalConditions.size) {
            0 -> Condition.Always()
            1 -> totalConditions[0]
            else -> Condition.And(totalConditions)
        }
    }

    /**
     * A helper class for building a mask.
     */
    class Builder<T>(
        val pairs: ArrayList<Pair<Condition<T>, Modification<T>>> = ArrayList()
    ) {
        val it = startChain<T>()

        fun <V> PropChain<T, V>.mask(value: V, unless: Condition<T> = Condition.Always()) {
            pairs.add(unless to mapModification(Modification.Assign(value)))
        }

        @Deprecated("Replaced with functions that cause fewer mistakes - try mask instead.")
        infix fun <V> PropChain<T, V>.maskedTo(value: V) = mapModification(Modification.Assign(value))
        @Deprecated("Replaced with functions that cause fewer mistakes - try mask instead.")
        infix fun Modification<T>.unless(condition: Condition<T>) {
            pairs.add(condition to this)
        }
        @Deprecated("Replaced with functions that cause fewer mistakes - try mask instead.")
        fun always(modification: Modification<T>) {
            pairs.add(Condition.Never<T>() to modification)
        }

        fun build() = Mask(pairs)
        fun include(mask: Mask<T>) {
            pairs.addAll(mask.pairs)
        }
    }
}

/**
 * DSL for creating a [Mask].
 */
inline fun <T> mask(builder: Mask.Builder<T>.() -> Unit): Mask<T> {
    return Mask.Builder<T>().apply(builder).build()
}
