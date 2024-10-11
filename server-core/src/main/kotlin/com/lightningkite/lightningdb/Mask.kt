package com.lightningkite.lightningdb

import com.lightningkite.serialization.*
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class Mask<T>(
    /**
     * If the condition does not pass, then the modification will be applied to mask the values.
     */
    val pairs: List<Pair<Condition<T>, Modification<T>>> = listOf()
) {
    companion object {
        val logger = LoggerFactory.getLogger("com.lightningkite.lightningdb.Mask")
    }
    operator fun invoke(on: T): T {
        var value = on
        for(pair in pairs) {
            if(!pair.first(on)) value = pair.second(value)
        }
        return value
    }
    operator fun invoke(on: Partial<T>): Partial<T> {
        var value = on
        for(pair in pairs) {
            val evaluated = pair.first(on)
            if(evaluated != true) value = pair.second(value)
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
    operator fun invoke(condition: Condition<T>, tableTextPaths: List<List<SerializableProperty<*, *>>> = listOf()): Condition<T> {
        val totalConditions = ArrayList<Condition<T>>()
        for(pair in pairs) {
            if(condition.readsResultOf(pair.second, tableTextPaths)) totalConditions.add(pair.first)
        }
        return when(totalConditions.size) {
            0 -> Condition.Always()
            1 -> totalConditions[0]
            else -> Condition.And(totalConditions)
        }
    }
    class Builder<T>(
        serializer: KSerializer<T>,
        val pairs: ArrayList<Pair<Condition<T>, Modification<T>>> = ArrayList()
    ) {
        val it = DataClassPathSelf(serializer)
        fun <V> DataClassPath<T, V>.mask(value: V, unless: Condition<T> = Condition.Never()) {
            pairs.add(unless to mapModification(Modification.Assign(value)))
        }
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

inline fun <reified T> mask(builder: Mask.Builder<T>.(DataClassPath<T, T>)->Unit): Mask<T> {
    return Mask.Builder<T>(Serialization.module.contextualSerializerIfHandled()).apply { builder(path()) }.build()
}

operator fun <T> Condition<T>.invoke(map: Partial<T>): Boolean? {
    return when(this) {
        is Condition.Always -> true
        is Condition.Never -> false
        is Condition.And -> {
            val results = this.conditions.map { it(map) }
            if(results.any { it == false }) false
            else if(results.any { it == null }) null
            else true
        }
        is Condition.Or -> {
            val results = this.conditions.map { it(map) }
            if(results.any { it == true }) true
            else if(results.any { it == null }) null
            else true
        }
        is Condition.Not -> condition(map)?.not()
        is Condition.OnField<*, *> -> if(map.parts.containsKey(key)) map.parts[key].let {
            @Suppress("UNCHECKED_CAST")
            if(it is Partial<*>) (condition as Condition<Any?>).invoke(map = it as Partial<Any?>)
            else (condition as Condition<Any?>)(it)
        } else null
        else -> null
    }
}
@Suppress("UNCHECKED_CAST")
operator fun <T> Modification<T>.invoke(map: Partial<T>): Partial<T> {
    return when(this) {
        is Modification.OnField<*, *> -> if(map.parts.containsKey(key)) {
            val newPartial = Partial<T>(map.parts.toMutableMap())
            newPartial.parts[key as SerializableProperty<T, *>] = map.parts[key].let {
                if (it is Partial<*>) (modification as Modification<Any?>)(it as Partial<Any?>)
                else (modification as Modification<Any?>)(it)
            }
            newPartial
        } else map
        else -> map
    }
}
