package com.lightningkite.lightningdb

import kotlinx.serialization.Serializable

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
            if(!pair.first(on)) value = pair.second(value)
        }
        return value
    }
    operator fun invoke(on: Map<String, Any?>): Map<String, Any?> {
        var value = on
        for(pair in pairs) {
            if(pair.first(on) == false) value = pair.second(value)
        }
        return value
    }
    fun permitSort(on: List<SortPart<T>>): Condition<T> {
        val totalConditions = ArrayList<Condition<T>>()
        for(pair in pairs) {
            if(on.any { pair.second.affects(it.field) }) totalConditions.add(pair.first)
        }
        return when(totalConditions.size) {
            0 -> Condition.Always()
            1 -> totalConditions[0]
            else -> Condition.And(totalConditions)
        }
    }
    operator fun invoke(on: DataClassPathPartial<T>): Condition<T> {
        val totalConditions = ArrayList<Condition<T>>()
        for(pair in pairs) {
            if(pair.second.affects(on)) totalConditions.add(pair.first)
        }
        return when(totalConditions.size) {
            0 -> Condition.Always()
            1 -> totalConditions[0]
            else -> Condition.And(totalConditions)
        }
    }
    operator fun invoke(condition: Condition<T>): Condition<T> {
        val totalConditions = ArrayList<Condition<T>>()
        for(pair in pairs) {
            if(condition.readsResultOf(pair.second)) totalConditions.add(pair.first)
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
        val it = path<T>()
        infix fun <V> DataClassPath<T, V>.maskedTo(value: V) = mapModification(Modification.Assign(value))
        infix fun Modification<T>.unless(condition: Condition<T>) {
            pairs.add(condition to this)
        }
        fun always(modification: Modification<T>) {
            pairs.add(Condition.Never<T>() to modification)
        }
        fun build() = Mask(pairs)
        fun include(mask: Mask<T>) { pairs.addAll(mask.pairs) }
    }
}

inline fun <T> mask(builder: Mask.Builder<T>.()->Unit): Mask<T> {
    return Mask.Builder<T>().apply(builder).build()
}

operator fun <T> Condition<T>.invoke(map: Map<String, Any?>): Boolean? {
    return when(this) {
        is Condition.OnField<*, *> -> if(map.containsKey(key.name)) map[key.name].let {
            if(it is Map<*, *>) condition(it as Map<String, Any?>)
            else (condition as Condition<Any?>)(it)
        } else null
        else -> null
    }
}
operator fun <T> Modification<T>.invoke(map: Map<String, Any?>): Map<String, Any?> {
    return when(this) {
        is Modification.OnField<*, *> -> if(map.containsKey(key.name)) map + (key.name to map[key.name].let {
            if(it is Map<*, *>) modification(it as Map<String, Any?>)
            else (modification as Modification<Any?>)(it)
        }) else map
        else -> map
    }
}
