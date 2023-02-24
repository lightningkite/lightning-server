package com.lightningkite.lightningdb

import kotlin.reflect.KProperty1

data class ModelPermissions<Model>(
    val create: Condition<Model> = Condition.Never(),
    val read: Condition<Model> = Condition.Never(),
    val readMask: Mask<Model> = Mask(listOf()),
    val update: Condition<Model> = Condition.Never(),
    val updateRestrictions: UpdateRestrictions<Model> = UpdateRestrictions(listOf()),
    val delete: Condition<Model> = Condition.Never(),
    val maxQueryTimeMs: Long = 1_000L
) {
    companion object {
        fun <Model> allowAll():ModelPermissions<Model> = ModelPermissions(
            create = Condition.Always(),
            read = Condition.Always(),
            update = Condition.Always(),
            delete = Condition.Always(),
        )
    }
    fun allowed(modification: Modification<Model>): Condition<Model> = updateRestrictions(modification) and update
    fun mask(model: Model): Model = readMask(model)
}