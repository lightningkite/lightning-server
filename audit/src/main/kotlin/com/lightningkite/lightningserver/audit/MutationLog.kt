package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.generateFromServerClock
import com.lightningkite.services.data.Unsafe
import com.lightningkite.services.data.UuidV7
import com.lightningkite.services.database.EntryChange
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.Partial
import com.lightningkite.services.database.SerializableProperty
import com.lightningkite.services.database.nullElement
import com.lightningkite.services.database.serializableProperties
import com.lightningkite.services.database.toPartialEntry
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.partialOfDifference
import com.lightningkite.services.database.postCreate
import com.lightningkite.services.database.postRawChanges
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.modules.SerializersModule
import kotlin.uuid.Uuid

context(serverRuntime: ServerRuntime)
public fun <T : HasId<ID>, ID : Comparable<ID>> Table<T>.logAuditedFieldChanges(log: MutationLog): Table<T> =
    postRawChanges { changes -> log.logAuditedFieldChanges(serializer, changes) }

public class MutationLog(
    private val origins: OriginRecording<*>,
    private val registry: AuditRegistry,
    public val table: Runtime<Table<MutationRecord>>,
) {
    context(runtime: ServerRuntime)
    public suspend fun <T : Any> logAuditedFieldChanges(serializer: KSerializer<T>, changes: List<EntryChange<T>>) {
        val type = registry.assignments.await().modelType(serializer.descriptor)

        val logs = changes.mapNotNull { (old, new) ->
            if (old == new) return@mapNotNull null
            val item = new ?: old ?: return@mapNotNull null

            val newData = auditedPartialOfDifference(serializer, old, new)

            if (newData != null && newData.parts.isEmpty()) return@mapNotNull null

            @OptIn(Unsafe::class) // SAFETY: flush is called later once we know we are actually inserting something
            MutationRecord(
                _id = MutationRecord.ID(UuidV7.generateFromServerClock()),
                origin = origins.currentOrigin(),
                modelType = type,
                operation = when {
                    old == null -> MutationRecord.Operation.Create
                    new == null -> MutationRecord.Operation.Delete
                    else -> MutationRecord.Operation.Modify
                },
                modelId = idOf(serializer, item),
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

    /**
     * Returns the `_id` of [value], encoding only until it is found.
     *
     * @throws IllegalStateException if [value] has no `Uuid` `_id`.
     */
    context(runtime: ServerRuntime)
    private fun <T> idOf(serializer: SerializationStrategy<T>, value: T): Uuid =
        IdEncoder(runtime.internalSerialization.json.serializersModule)
            .also { it.encodeSerializableValue(serializer, value) }
            .id
            ?: throw IllegalStateException("${serializer.descriptor.serialName} has no Uuid _id to record a mutation against.")

    @OptIn(ExperimentalSerializationApi::class)
    private class IdEncoder(override val serializersModule: SerializersModule) : AbstractEncoder() {
        var id: Uuid? = null
        private var inId = false

        // Claude: returning false makes the encoder skip the element, so everything after the id is skipped.
        override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
            if (id != null) return false
            inId = descriptor.getElementName(index) == "_id"
            return true
        }

        // Claude: value classes such as typed ids and UuidV7 pass their inner value through here, so the Uuid is caught however it is wrapped.
        override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
            if (id != null) return
            if (inId && value is Uuid) id = value
            else super.encodeSerializableValue(serializer, value)
        }

        override fun encodeValue(value: Any) {}
        override fun encodeNull() {}
    }
}

/**
 * Returns a [Partial] of the [Audited] properties of [new] that differ from [old], or `null` if [new] is `null`.
 *
 * Works like [partialOfDifference], but leaves out properties that neither are marked [Audited] nor hold one.
 * A nested class that is not [Audited] is compared property by property. A list, map or sealed value holding
 * an audited property is included whole, since a [Partial] cannot describe part of one. A nested [Audited]
 * model is left out, since it gets records of its own. When [old] is `null`, every audited property of [new]
 * is included.
 */
@OptIn(ExperimentalSerializationApi::class)
private fun <T> auditedPartialOfDifference(serializer: KSerializer<T>, old: T?, new: T?): Partial<T>? {
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

            type.isAudited || !type.containsAuditedFields() -> {}
            isComposite && newValue != null ->
                auditedPartialOfDifference(property.serializer, oldValue, newValue)!!
                    .takeIf { it.parts.isNotEmpty() }
                    ?.let { partial.parts[property] = it }

            else -> partial.parts[property] = property.toPartialEntry(newValue)
        }
    }
    return partial
}
