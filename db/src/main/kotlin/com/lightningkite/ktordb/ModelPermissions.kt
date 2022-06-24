package com.lightningkite.ktordb

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.reflect.KProperty1

data class ModelPermissions<Model>(
    val create: Condition<Model>,
    val read: Condition<Model>,
    val readFields: Map<KProperty1<Model, *>, Read<Model, *>> = mapOf(),
    val update: Condition<Model>,
    val updateFields: Map<KProperty1<Model, *>, Update<Model, *>> = mapOf(),
    val delete: Condition<Model>,
    val maxQueryTimeMs: Long = 1_000L
) {
    data class Read<Model, Field>(
        val property: KProperty1<Model, Field>,
        val condition: Condition<Model>,
        val mask: Field
    ) {
        @Suppress("UNCHECKED_CAST")
        fun mask(model: Model): Model {
            return if(condition(model)) model
            else property.setCopy(model, mask)
        }
    }
    data class Update<Model, Field>(
        val property: KProperty1<Model, Field>,
        val condition: Condition<Model>
    )

    fun allowed(modification: Modification<*>): Condition<Model> {
        return when(modification) {
            is Modification.Assign<*> -> Condition.And(updateFields.values.map { it.condition })
            is Modification.Chain<*> -> Condition.And(modification.modifications.map { allowed(it) })
            is Modification.IfNotNull<*> -> allowed(modification.modification)
            is Modification.OnField<*, *> -> allowedInner(modification)
            else -> Condition.Always()
        }
    }
    private fun allowedInner(modification: Modification<*>): Condition<Model> {
        return when(modification) {
            is Modification.OnField<*, *> -> updateFields[modification.key]?.condition ?: Condition.Always()
            is Modification.Chain<*> -> Condition.And(modification.modifications.map { allowedInner(it) })
            is Modification.IfNotNull<*> -> allowed(modification.modification)
            else -> Condition.Always()
        }
    }

    fun mask(model: Model): Model {
        var current = model
        for(f in readFields.values) {
            current = f.mask(current)
        }
        return current
    }
}