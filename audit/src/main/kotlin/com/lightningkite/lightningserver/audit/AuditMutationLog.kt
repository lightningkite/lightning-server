package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.generateFromServerClock
import com.lightningkite.lightningserver.typed.registerTable
import com.lightningkite.services.data.Unsafe
import com.lightningkite.services.data.UuidV7
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.EntryChange
import com.lightningkite.services.database.Partial
import com.lightningkite.services.database.SerializableProperty
import com.lightningkite.services.database.nullElement
import com.lightningkite.services.database.serializableProperties
import com.lightningkite.services.database.toPartialEntry
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.partialOfDifference
import com.lightningkite.services.database.postRawChanges
import kotlinx.serialization.KSerializer

/**
 * Returns this table wrapped so that every change it makes to an [Audited] field is recorded in [log].
 *
 * Each change is recorded after it is made, through [AuditMutationLog.logAuditedFieldChanges]. The
 * `…IgnoringResult` and `…IgnoringOld` calls run as their effect-returning equivalents so the changed rows can
 * be recorded.
 *
 * The model must be registered in the [AuditRegistry] passed to [log]. The registry registers the
 * [AuditedModel]s used by the server's endpoints when pre-deploy tasks run, so a model that no endpoint uses
 * has no entry. Changes to an unregistered model throw [IllegalStateException] *after* the change has been
 * made, so the change stands but the caller sees a failure.
 */
context(serverRuntime: ServerRuntime)
public fun <T : AuditedModel<ID>, ID : AuditedModel.Id<*, ID>> Table<T>.logAuditedFieldChanges(log: AuditMutationLog): Table<T> =
    postRawChanges { changes -> log.logAuditedFieldChanges(serializer, changes) }

context(builder: ServerBuilder)
public fun AuditMutationLog(
    origins: OriginRecording<*>,
    registry: AuditRegistry,
    database: Runtime<Database>
): AuditMutationLog = AuditMutationLog(
    origins, registry,
    database.registerTable("AuditMutationRecord")
)

public class AuditMutationLog(
    public val origins: OriginRecording<*>,
    public val registry: AuditRegistry,
    public val table: Runtime<Table<AuditMutationRecord>>,
) {
    /**
     * Records each of [changes] that touches an [Audited] field of [T] as one [AuditMutationRecord] under the
     * current origin.
     *
     * A create records every audited field of the new value, a modify records only the audited fields that
     * changed, and a delete records no data. A change that touches no audited field is not recorded. Audited
     * fields of nested classes are recorded too, including those of embedded [AuditedModel]s.
     *
     * The current origin is written to the [OriginRecording] only if at least one record is written.
     *
     * @throws IllegalStateException if [T] has no entry in the [AuditRegistry], which happens when no endpoint
     *   uses it.
     */
    context(runtime: ServerRuntime)
    public suspend fun <T : AuditedModel<*>> logAuditedFieldChanges(serializer: KSerializer<T>, changes: List<EntryChange<T>>) {
        val type = registry.assignments.await().modelType(serializer.descriptor)

        val logs = changes.mapNotNull { (old, new) ->
            if (old == new) return@mapNotNull null
            val item = new ?: old ?: return@mapNotNull null

            val newData = partialOfAuditedDifference(serializer, old, new)

            // null means the value was deleted, we want to record that, 
            if (newData != null && newData.parts.isEmpty()) return@mapNotNull null

            @OptIn(Unsafe::class) // SAFETY: flush is called later once we know we are actually inserting something
            AuditMutationRecord(
                _id = AuditMutationRecord.ID(UuidV7.generateFromServerClock()),
                origin = origins.currentOrigin(),
                modelType = type,
                operation = when {
                    old == null -> AuditMutationRecord.Operation.Create
                    new == null -> AuditMutationRecord.Operation.Delete
                    else -> AuditMutationRecord.Operation.Modify
                },
                modelId = item._id.asUuid,
                data = newData?.let {
                    runtime.internalSerialization.json.encodeToString(
                        Partial.serializer(serializer),
                        newData
                    )
                }
            )
        }

        if (logs.isEmpty()) return

        origins.flush()
        table().insert(logs)
    }
}

/**
 * Returns a [Partial] of the [Audited] properties of [new] that differ from [old], or `null` if [new] is `null`.
 *
 * Works like [partialOfDifference], but leaves out properties that neither are marked [Audited] nor hold one.
 * A nested class, including an embedded [AuditedModel], is compared property by property. A list, map or
 * sealed value holding an audited property is included whole, since a [Partial] cannot describe part of one.
 * When [old] is `null`, every audited property of [new] is included.
 */
private fun <T> partialOfAuditedDifference(serializer: KSerializer<T>, old: T?, new: T?): Partial<T>? {
    if (new == null) return null
    @Suppress("UNCHECKED_CAST")
    val unwrapped = (serializer.nullElement() ?: serializer) as KSerializer<T>
    val descriptor = unwrapped.descriptor
    val partial = Partial<T>()
    for (property in unwrapped.serializableProperties!!) {
        @Suppress("UNCHECKED_CAST")
        property as SerializableProperty<T, Any?>
        val oldValue = old?.let(property::get)
        val newValue = property.get(new)
        if (old != null && oldValue == newValue) continue

        val index = descriptor.getElementIndex(property.name)
        val type = descriptor.getElementDescriptor(index)
        val isComposite = !type.isInline && (property.serializer.nullElement() ?: property.serializer).serializableProperties != null
        when {
            descriptor.isElementAudited(index) -> partial.parts[property] =
                if (isComposite) partialOfDifference(property.serializer, oldValue, newValue) else newValue

            !type.containsAuditedFields() -> {}
            isComposite && newValue != null ->
                partialOfAuditedDifference(property.serializer, oldValue, newValue)!!
                    .takeIf { it.parts.isNotEmpty() }
                    ?.let { partial.parts[property] = it }

            else -> partial.parts[property] = property.toPartialEntry(newValue)
        }
    }
    return partial
}
