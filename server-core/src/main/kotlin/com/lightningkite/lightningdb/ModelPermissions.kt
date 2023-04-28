package com.lightningkite.lightningdb

import kotlin.reflect.KProperty1

/**
 * Defines permissions for accessing a model in a database.
 * Default constructor is 'whitelist' mode.
 */
data class ModelPermissions<Model>(
    /**
     * The user may only create an item if it matches this condition.
     */
    val create: Condition<Model> = Condition.Never(),
    /**
     * The user may only read models that match this condition.
     */
    val read: Condition<Model> = Condition.Never(),
    /**
     * The user may only read models masked as defined here.
     */
    val readMask: Mask<Model> = Mask(listOf()),
    /**
     * The user may only update models that match this condition.
     */
    val update: Condition<Model> = Condition.Never(),
    /**
     * Restrictions on what the user is allowed to update.
     */
    val updateRestrictions: UpdateRestrictions<Model> = UpdateRestrictions(listOf()),
    /**
     * The user may only delete models that match this condition.
     */
    val delete: Condition<Model> = Condition.Never(),
    val maxQueryTimeMs: Long = 1_000L
) {
    companion object {
        /**
         * A full whitelist permission set.
         */
        fun <Model> allowAll(): ModelPermissions<Model> = ModelPermissions(
            create = Condition.Always(),
            read = Condition.Always(),
            update = Condition.Always(),
            delete = Condition.Always(),
        )
    }


    @Deprecated("Use Mask instead of individual definitions like the ones below")
    data class Read<Model, Field>(
        val property: KProperty1<Model, Field>,
        val condition: Condition<Model>,
        val mask: Field
    )

    @Deprecated("Use UpdateRestrictions instead of individual definitions like the ones below")
    data class Update<Model, Field>(
        val property: KProperty1<Model, Field>,
        val condition: Condition<Model>
    )

    @Deprecated("Use the new masks and update restriction objects.")
    constructor(
        create: Condition<Model>,
        read: Condition<Model>,
        readFields: Map<KProperty1<Model, *>, Read<Model, *>>,
        update: Condition<Model>,
        delete: Condition<Model>,
        maxQueryTimeMs: Long = 1_000L
    ) : this(
        create = create,
        read = read,
        readMask = Mask(readFields.values.map {
            it.condition to Modification.OnField(
                it.property,
                Modification.Assign(it.mask)
            )
        }),
        update = update,
        updateRestrictions = UpdateRestrictions(listOf()),
        delete = delete,
        maxQueryTimeMs = maxQueryTimeMs,
    )

    @Deprecated("Use the new masks and update restriction objects.")
    constructor(
        create: Condition<Model>,
        read: Condition<Model>,
        readFields: Map<KProperty1<Model, *>, Read<Model, *>> = mapOf(),
        update: Condition<Model>,
        updateFields: Map<KProperty1<Model, *>, Update<Model, *>>,
        delete: Condition<Model>,
        maxQueryTimeMs: Long = 1_000L
    ) : this(
        create = create,
        read = read,
        readMask = Mask(readFields.values.map {
            it.condition to Modification.OnField(
                it.property,
                Modification.Assign(it.mask)
            )
        }),
        update = update,
        updateRestrictions = UpdateRestrictions(updateFields.values.map {
            Triple(
                Modification.OnField(
                    it.property,
                    Modification.Assign(null)
                ), it.condition, Condition.Always()
            )
        }),
        delete = delete,
        maxQueryTimeMs = maxQueryTimeMs,
    )

    /**
     * @return a condition defining under what circumstances the given [modification] is permitted in.
     */
    fun allowed(modification: Modification<Model>): Condition<Model> = updateRestrictions(modification) and update

    /**
     * Masks a single instance of the model.
     */
    fun mask(model: Model): Model = readMask(model)
}