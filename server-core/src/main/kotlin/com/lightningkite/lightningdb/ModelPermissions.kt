package com.lightningkite.lightningdb

import com.lightningkite.serialization.*
import com.lightningkite.serialization.SerializableProperty

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

    constructor(
        read: Condition<Model>,
        readMask: Mask<Model> = Mask(),
        manage: Condition<Model>,
        updateRestriction: UpdateRestrictions<Model> = UpdateRestrictions(),
    ) : this(
        create = manage,
        update = manage,
        updateRestrictions = updateRestriction,
        read = read,
        readMask = readMask,
        delete = manage
    )
    constructor(
        all: Condition<Model>,
        readMask: Mask<Model> = Mask(),
        updateRestriction: UpdateRestrictions<Model> = UpdateRestrictions(),
    ) : this(
        create = all,
        update = all,
        updateRestrictions = updateRestriction,
        read = all,
        readMask = readMask,
        delete = all
    )

    /**
     * @return a condition defining under what circumstances the given [modification] is permitted in.
     */
    fun allowed(modification: Modification<Model>): Condition<Model> = updateRestrictions(modification) and update

    /**
     * Masks a single instance of the model.
     */
    fun mask(model: Model): Model = readMask(model)

    /**
     * Masks a single instance of the model.
     */
    fun mask(model: Partial<Model>): Partial<Model> = readMask(model)
}