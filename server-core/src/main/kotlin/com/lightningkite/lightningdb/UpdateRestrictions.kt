package com.lightningkite.lightningdb

import kotlinx.serialization.Serializable

@Serializable
data class UpdateRestrictions<T>(
    /**
     * If the modification matches paths, then the condition is applied to the update
     */
    val fields: List<Part<T>> = listOf()
) {
    @Serializable
    data class Part<T>(val path: KeyPathPartial<T>, val limitedIf: Condition<T>, val limitedTo: Condition<T>)

    operator fun invoke(on: Modification<T>): Condition<T> {
        val totalConditions = ArrayList<Condition<T>>()
        for(field in fields) {
            if(on.affects(field.path)) {
                totalConditions.add(field.limitedIf)
                if(field.limitedTo !is Condition.Always) {
                    if(!field.limitedTo.guaranteedAfter(on)) return Condition.Never()
                }
            }
        }
        return when(totalConditions.size) {
            0 -> Condition.Always()
            1 -> totalConditions[0]
            else -> Condition.And(totalConditions)
        }
    }

    class Builder<T>(
        val fields: ArrayList<Part<T>> = ArrayList()
    ) {
        val it = path<T>()
        fun KeyPath<T, *>.cannotBeModified() {
            fields.add(Part(this, Condition.Never(), Condition.Always()))
        }
        infix fun KeyPath<T, *>.requires(condition: Condition<T>) {
            fields.add(Part(this, condition, Condition.Always()))
        }
        fun <V> KeyPath<T, V>.requires(requires: Condition<T>, valueMust: (KeyPath<V, V>)->Condition<V>) {
            fields.add(Part(this, requires, this.condition(valueMust)))
        }
        fun <V> KeyPath<T, V>.mustBe(valueMust: (KeyPath<V, V>)->Condition<V>) {
            fields.add(Part(this, Condition.Always(), this.condition(valueMust)))
        }
        fun build() = UpdateRestrictions(fields)
        fun include(mask: UpdateRestrictions<T>) { fields.addAll(mask.fields) }
    }
}

inline fun <T> updateRestrictions(builder: UpdateRestrictions.Builder<T>.()->Unit): UpdateRestrictions<T> {
    return UpdateRestrictions.Builder<T>().apply(builder).build()
}
