package com.lightningkite.lightningdb

import kotlinx.serialization.Serializable
import kotlin.reflect.KProperty1

@Serializable
data class UpdateRestrictions<T>(
    /**
     * If the modification matches paths, then the condition is applied to the update
     */
    val pairs: List<Pair<Modification<T>, Condition<T>>> = listOf()
) {
    private fun path(modification: Modification<*>): List<KProperty1<*, *>> {
        return when(modification) {
            is Modification.OnField<*, *> -> listOf(modification.key) + path(modification.modification)
            is Modification.SetPerElement<*> -> path(modification.modification)
            is Modification.ListPerElement<*> -> path(modification.modification)
            else -> listOf()
        }
    }
    private fun matches(modification: Modification<*>, list: List<KProperty1<*, *>>): Boolean {
        if(list.isEmpty()) return true
        return when(modification) {
            is Modification.OnField<*, *> -> {
                list.firstOrNull() == modification.key && matches(modification.modification, list.drop(1))
            }
            is Modification.SetPerElement<*> -> matches(modification.modification, list)
            is Modification.ListPerElement<*> -> matches(modification.modification, list)
            is Modification.Chain -> modification.modifications.any { matches(it, list) }
            else -> false
        }
    }
    operator fun invoke(on: Modification<T>): Condition<T> {
        val totalConditions = ArrayList<Condition<T>>()
        for(pair in pairs) {
            val path = path(pair.first)
            if(matches(on, path)) {
                totalConditions.add(pair.second)
            }
        }
        return when(totalConditions.size) {
            0 -> Condition.Always()
            1 -> totalConditions[0]
            else -> Condition.And(totalConditions)
        }
    }

    class Builder<T>(
        val pairs: ArrayList<Pair<Modification<T>, Condition<T>>> = ArrayList()
    ) {
        val it = startChain<T>()
        fun PropChain<T, *>.cannotBeModified() {
            pairs.add(
                this.let {
                    @Suppress("UNCHECKED_CAST")
                    it as PropChain<T, Any?>
                }.assign(null) to Condition.Never()
            )
        }
        infix fun PropChain<T, *>.requires(condition: Condition<T>) {
            pairs.add(
                this.let {
                    @Suppress("UNCHECKED_CAST")
                    it as PropChain<T, Any?>
                }.assign(null) to condition
            )
        }
        fun build() = UpdateRestrictions(pairs)
    }
}

inline fun <T> updateRestrictions(builder: UpdateRestrictions.Builder<T>.()->Unit): UpdateRestrictions<T> {
    return UpdateRestrictions.Builder<T>().apply(builder).build()
}
