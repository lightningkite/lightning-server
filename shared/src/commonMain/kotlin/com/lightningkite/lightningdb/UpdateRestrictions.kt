package com.lightningkite.lightningdb

import com.lightningkite.serialization.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

@Serializable
@GenerateDataClassPaths
data class UpdateRestrictionsPart<T>(
    val path: DataClassPathPartial<T>,
    val limitedIf: Condition<T>,
    val limitedTo: Condition<T>
)

/**
 * Permission rules regarding updating items per-field.
 */
@GenerateDataClassPaths
@Serializable
data class UpdateRestrictions<T>(
    /**
     * If the modification matches paths, then the condition is applied to the update
     */
    val fields: List<UpdateRestrictionsPart<T>> = listOf()
) {

    /**
     * The condition a row must meet for [on] to be allowed: the [UpdateRestrictionsPart.limitedIf] of every rule whose
     * field [on] writes (including by writing a parent of it), plus what must already hold for its
     * [UpdateRestrictionsPart.limitedTo] to hold afterwards (see [requiredBefore]).
     */
    operator fun invoke(on: Modification<T>): Condition<T> {
        val totalConditions = LinkedHashSet<Condition<T>>()
        for(field in fields) {
            if(on.affects(field.path)) {
                val limitedToBefore = field.limitedTo.requiredBefore(on)
                if(limitedToBefore == Condition.Never || field.limitedIf == Condition.Never) return Condition.Never
                if(limitedToBefore != Condition.Always) totalConditions.add(limitedToBefore)
                if(field.limitedIf != Condition.Always) totalConditions.add(field.limitedIf)
            }
        }
        return when(totalConditions.size) {
            0 -> Condition.Always
            1 -> totalConditions.single()
            else -> Condition.And(totalConditions.toList())
        }
    }

    class Builder<T>(
        serializer: KSerializer<T>,
        val fields: ArrayList<UpdateRestrictionsPart<T>> = ArrayList()
    ) {
        val it = DataClassPathSelf(serializer)
        /**
         * Makes a field unmodifiable.
         */
        fun DataClassPath<T, *>.cannotBeModified() {
            fields.add(UpdateRestrictionsPart(this, Condition.Never, Condition.Always))
        }
        /**
         * Makes a field only modifiable if the item matches the [condition].
         * Read masks don't apply to [condition], so whether an update matched can reveal a masked value it tests.
         */
        infix fun DataClassPath<T, *>.requires(condition: Condition<T>) {
            fields.add(UpdateRestrictionsPart(this, condition, Condition.Always))
        }
        /**
         * Makes a field only modifiable if the item matches the [condition].
         * In addition, the value it is being changed to must match [valueMust].
         */
        inline fun <reified V> DataClassPath<T, V>.requires(requires: Condition<T>, valueMust: (DataClassPath<V, V>)->Condition<V>) {
            fields.add(UpdateRestrictionsPart(this, requires, this.condition(valueMust)))
        }
        /**
         * The value is only allowed to change to a value that matches [valueMust].
         * A write that doesn't set the whole field is only allowed on rows where the parts of [valueMust] it leaves
         * alone already hold, which is added to the update's condition; see [requiredBefore].
         */
        inline fun <reified V> DataClassPath<T, V>.mustBe(valueMust: (DataClassPath<V, V>)->Condition<V>) {
            fields.add(UpdateRestrictionsPart(this, Condition.Always, this.condition(valueMust)))
        }
        fun build() = UpdateRestrictions(fields)
        fun include(mask: UpdateRestrictions<T>) { fields.addAll(mask.fields) }
    }
}

/**
 * DSL for defining [UpdateRestrictions]
 */
inline fun <reified T> updateRestrictions(builder: UpdateRestrictions.Builder<T>.(DataClassPath<T, T>)->Unit): UpdateRestrictions<T> {
    return UpdateRestrictions.Builder<T>(serializer()).apply { builder(path<T>()) }.build()
}
