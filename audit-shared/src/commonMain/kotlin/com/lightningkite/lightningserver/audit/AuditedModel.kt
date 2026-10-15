package com.lightningkite.lightningserver.audit

import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.TypedId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InheritableSerialInfo
import kotlinx.serialization.SerialInfo
import kotlin.uuid.Uuid

/**
 * Marks a model as audited: every instance that reaches a client produces its own [DisclosureRecord], and its
 * changes can be recorded as [AuditMutationRecord]s.
 *
 * Mark the fields worth itemising with [Audited]. A model with no such fields is still audited, but its
 * records only say which record was involved, not which fields.
 *
 * ```kotlin
 * @Serializable
 * data class Patient(
 *     override val _id: ID,
 *     @Audited val ssn: String,
 * ) : AuditedModel<Patient.ID> {
 *     @Serializable
 *     @JvmInline
 *     value class ID(override val raw: Uuid) : AuditedModel.Id<Uuid, ID> {
 *         override val asUuid: Uuid get() = raw
 *     }
 * }
 * ```
 *
 * The model must be `@Serializable` and reach a client through some endpoint, since the [AuditedModelMarker]
 * this applies is what the audit registry finds when it scans endpoint serializers.
 */
@AuditedModelMarker
public interface AuditedModel<ID : AuditedModel.Id<*, ID>> : HasId<ID> {
    /**
     * Represents the id of an [AuditedModel], which must wrap a [Uuid] at some depth.
     *
     * The raw value may be the [Uuid] itself or another type that wraps one, such as a `UuidV7`. Audit records
     * name the model by [asUuid], so a record stays sixteen bytes wide whatever the id's own type is.
     *
     * ```kotlin
     * @Serializable
     * @JvmInline
     * value class ID(override val raw: UuidV7) : AuditedModel.Id<UuidV7, ID> {
     *     override val asUuid: Uuid get() = raw.raw
     * }
     * ```
     */
    public interface Id<ID : Comparable<ID>, TYPE : Id<ID, TYPE>> : TypedId<ID, TYPE> {
        /**
         * Returns the [Uuid] this id wraps.
         *
         * Must be the [Uuid] inside [raw], so that two ids are equal exactly when their [asUuid] values are equal.
         */
        public val asUuid: Uuid
    }
}

/**
 * Marks a property of an [AuditedModel] as itemized.
 *
 * A bit is reserved for the property, so each [DisclosureRecord] says whether it carried a value in what
 * the client received, and each [AuditMutationRecord] includes it when it changes. Itemizing is opt-in
 * because bits are a permanent, limited resource: indices are never reused, so every annotated property
 * costs capacity for good.
 *
 * A marked property is picked up anywhere in the model, including inside nested classes that are not
 * themselves audited: `@Audited val street: String` inside an `Address` becomes `address.street`.
 *
 * This is a `@SerialInfo` annotation, so it only has an effect on `@Serializable` classes.
 */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Audited

/**
 * Puts [AuditedModel] on the serial descriptor of every class that implements it.
 *
 * Applied through [AuditedModel]; implement that instead of using this directly.
 */
@OptIn(ExperimentalSerializationApi::class)
@InheritableSerialInfo
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class AuditedModelMarker
