package com.lightningkite.lightningdb

import kotlin.reflect.KProperty1

data class ModelPermissions<Model>(
    val create: Condition<Model>,
    val read: Condition<Model>,
    val readMask: Mask<Model> = Mask(listOf()),
    val update: Condition<Model>,
    val updateRestrictions: UpdateRestrictions<Model> = UpdateRestrictions(listOf()),
    val delete: Condition<Model>,
    val maxQueryTimeMs: Long = 1_000L
) {
    data class Read<Model, Field>(
        val property: KProperty1<Model, Field>,
        val condition: Condition<Model>,
        val mask: Field
    )
    data class Update<Model, Field>(
        val property: KProperty1<Model, Field>,
        val condition: Condition<Model>
    )
    constructor(
        create: Condition<Model>,
        read: Condition<Model>,
        readFields: Map<KProperty1<Model, *>, Read<Model, *>>,
        update: Condition<Model>,
        delete: Condition<Model>,
        maxQueryTimeMs: Long = 1_000L
    ):this(
        create = create,
        read = read,
        readMask = Mask(readFields.values.map { it.condition to Modification.OnField(it.property, Modification.Assign(it.mask)) }),
        update = update,
        updateRestrictions = UpdateRestrictions(listOf()),
        delete = delete,
        maxQueryTimeMs = maxQueryTimeMs,
    )
    constructor(
        create: Condition<Model>,
        read: Condition<Model>,
        readFields: Map<KProperty1<Model, *>, Read<Model, *>> = mapOf(),
        update: Condition<Model>,
        updateFields: Map<KProperty1<Model, *>, Update<Model, *>>,
        delete: Condition<Model>,
        maxQueryTimeMs: Long = 1_000L
    ):this(
        create = create,
        read = read,
        readMask = Mask(readFields.values.map { it.condition to Modification.OnField(it.property, Modification.Assign(it.mask)) }),
        update = update,
        updateRestrictions = UpdateRestrictions(updateFields.values.map { Modification.OnField(it.property, Modification.Assign(null)) to it.condition }),
        delete = delete,
        maxQueryTimeMs = maxQueryTimeMs,
    )

    fun allowed(modification: Modification<Model>): Condition<Model> = updateRestrictions(modification)
    fun mask(model: Model): Model = readMask(model)
}