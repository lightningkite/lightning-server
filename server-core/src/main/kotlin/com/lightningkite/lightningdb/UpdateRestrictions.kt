package com.lightningkite.lightningdb

import kotlinx.serialization.Serializable

@Serializable
data class UpdateRestrictions<T>(
    /**
     * If the modification matches paths, then the condition is applied to the update
     */
    val fields: List<Triple<Modification<T>, Condition<T>, Condition<T>>> = listOf()
) {

    operator fun invoke(on: Modification<T>): Condition<T> {
        val totalConditions = ArrayList<Condition<T>>()
        for (pair in fields) {
            if (on.matchesPath(pair.first)) {
                totalConditions.add(pair.second)
                if (pair.third !is Condition.Always) {
                    if (!pair.third.invoke(on)) return Condition.Never()
                }
            }
        }
        return when (totalConditions.size) {
            0 -> Condition.Always()
            1 -> totalConditions[0]
            else -> Condition.And(totalConditions)
        }
    }

    class Builder<T>(
        val fields: ArrayList<Triple<Modification<T>, Condition<T>, Condition<T>>> = ArrayList()
    ) {
        val it = startChain<T>()

        @Suppress("UNCHECKED_CAST")
        fun PropChain<T, *>.cannotBeModified() {
            fields.add(
                Triple(
                    modification { (this@cannotBeModified as PropChain<T, Any?>).assign(null) },
                    Condition.Never(),
                    Condition.Always()
                )
            )
        }

        @Suppress("UNCHECKED_CAST")
        infix fun PropChain<T, *>.requires(condition: Condition<T>) {
            fields.add(
                Triple(
                    modification { (this@requires as PropChain<T, Any?>).assign(null) }, condition, Condition.Always()
                )
            )
        }

        @Suppress("UNCHECKED_CAST")
        fun <V> PropChain<T, V>.restrict(requires: Condition<T>, valueMust: (PropChain<V, V>) -> Condition<V>) {
            fields.add(
                Triple(
                    modification { (this@restrict as PropChain<T, Any?>).assign(null) },
                    requires,
                    this.condition(valueMust)
                )
            )
        }

        @Suppress("UNCHECKED_CAST")
        fun <V> PropChain<T, V>.mustBe(valueMust: (PropChain<V, V>) -> Condition<V>) {
            fields.add(
                Triple(
                    modification { (this@mustBe as PropChain<T, Any?>).assign(null) },
                    Condition.Always(),
                    this.condition(valueMust)
                )
            )
        }

        fun build() = UpdateRestrictions(fields)
        fun include(mask: UpdateRestrictions<T>) {
            fields.addAll(mask.fields)
        }
    }
}

inline fun <T> updateRestrictions(builder: UpdateRestrictions.Builder<T>.() -> Unit): UpdateRestrictions<T> {
    return UpdateRestrictions.Builder<T>().apply(builder).build()
}
