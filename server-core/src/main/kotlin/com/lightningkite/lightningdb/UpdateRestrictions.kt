package com.lightningkite.lightningdb

import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * Permission rules regarding updating items per-field.
 */
@Serializable
data class UpdateRestrictions<T>(
    /**
     * If the modification matches paths, then the condition is applied to the update
     */
    val fields: List<Part<T>> = listOf()
) {
    @Serializable
    data class Part<T>(val path: DataClassPathPartial<T>, val limitedIf: Condition<T>, val limitedTo: Condition<T>)

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
        serializer: KSerializer<T>,
        val fields: ArrayList<Part<T>> = ArrayList()
    ) {
        val it = DataClassPathSelf(serializer)
        /**
         * Makes a field unmodifiable.
         */
        fun DataClassPath<T, *>.cannotBeModified() {
            fields.add(Part(this, Condition.Never(), Condition.Always()))
        }
        /**
         * Makes a field only modifiable if the item matches the [condition].
         */
        infix fun DataClassPath<T, *>.requires(condition: Condition<T>) {
            fields.add(Part(this, condition, Condition.Always()))
        }
        /**
         * Makes a field only modifiable if the item matches the [condition].
         * In addition, the value it is being changed to must match [valueMust].
         */
        inline fun <reified V> DataClassPath<T, V>.requires(requires: Condition<T>, valueMust: (DataClassPath<V, V>)->Condition<V>) {
            fields.add(Part(this, requires, this.condition(valueMust)))
        }
        /**
         * The value is only allowed to change to a value that matches [valueMust].
         */
        inline fun <reified V> DataClassPath<T, V>.mustBe(valueMust: (DataClassPath<V, V>)->Condition<V>) {
            fields.add(Part(this, Condition.Always(), this.condition(valueMust)))
        }
        fun build() = UpdateRestrictions(fields)
        fun include(mask: UpdateRestrictions<T>) { fields.addAll(mask.fields) }
    }
}

/**
 * DSL for defining [UpdateRestrictions]
 */
inline fun <reified T> updateRestrictions(builder: UpdateRestrictions.Builder<T>.(DataClassPath<T, T>)->Unit): UpdateRestrictions<T> {
    return UpdateRestrictions.Builder<T>(Serialization.module.contextualSerializerIfHandled()).apply { builder(path<T>()) }.build()
}
