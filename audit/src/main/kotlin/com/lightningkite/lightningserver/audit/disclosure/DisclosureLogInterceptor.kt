@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.lightningserver.audit.disclosure

import com.lightningkite.lightningserver.audit.AuditRegistry
import com.lightningkite.lightningserver.audit.DisclosureRecord
import com.lightningkite.lightningserver.audit.FieldIdentifierSet
import com.lightningkite.lightningserver.audit.ModelTypeId
import com.lightningkite.lightningserver.audit.OriginRecording
import com.lightningkite.lightningserver.audit.anythingAudited
import com.lightningkite.lightningserver.audit.auditSerialName
import com.lightningkite.lightningserver.audit.isAudited
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.generateFromServerClock
import com.lightningkite.lightningserver.serialization.EncodingInterceptor
import com.lightningkite.services.data.UuidV7
import com.lightningkite.services.database.PartialSerializer
import com.lightningkite.services.database.Table
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.SerializersModule
import kotlin.uuid.Uuid

/**
 * Writes one [com.lightningkite.lightningserver.audit.DisclosureRecord] for every audited record that reaches a client.
 *
 * ## Fail-closed
 *
 * Nothing is caught here on purpose. An extraction that cannot resolve a model, a record with no
 * `_id`, or a sink that will not accept the write all propagate out of
 * [EncodingInterceptor.beforeEncode], which aborts the send before the value is serialized. A
 * disclosure that could not be recorded does not happen.
 *
 * The practical consequence is worth stating plainly: an outage of the audit database is an outage
 * of every endpoint that returns an audited model.
 */
public class DisclosureLogInterceptor(
    private val registry: AuditRegistry,
    private val origins: OriginRecording<*>,
    private val table: Runtime<Table<DisclosureRecord>>,
) : EncodingInterceptor {
    override val name: String = "DisclosureLog"

    context(runtime: ServerRuntime)
    override suspend fun <T> beforeEncode(serializer: SerializationStrategy<T>, value: T) {
        if (!serializer.descriptor.anythingAudited()) return
        
        val disclosures = calculateDisclosures(serializer, value)
        if (disclosures.isEmpty()) return

        val rows = origins.withCurrentOrigin { origin ->
            disclosures.map {
                DisclosureRecord(
                    _id = DisclosureRecord.ID(UuidV7.generateFromServerClock()),
                    origin = origin,
                    recordType = it.model,
                    recordId = it.recordId,
                    disclosed = it.disclosed,
                )
            }
        }

        table().insert(rows)
    }

    /** One audited record that reached a client, and which of its fields carried a value. */
    internal data class Disclosure(
        val model: ModelTypeId,
        val recordId: Uuid,
        val disclosed: FieldIdentifierSet,
    )

    /**
     * The audited records inside [value], found by encoding it with [serializer].
     */
    context(runtime: ServerRuntime)
    internal suspend fun <T> calculateDisclosures(serializer: SerializationStrategy<T>, value: T): List<Disclosure> =
        DisclosureEncoder(registry.assignments.await(), runtime.externalSerialization.serializersModule)
            .also { it.encodeSerializableValue(serializer, value) }
            .found

    private class DisclosureEncoder(
        private val registry: AuditRegistry.Assignments,
        override val serializersModule: SerializersModule,
    ) : AbstractEncoder() {
        val found = ArrayList<Disclosure>()

        private class DisclosureBuilder(val modelId: ModelTypeId) {
            var bits: FieldIdentifierSet = FieldIdentifierSet.EMPTY
            var id: Uuid? = null
        }

        private class Structure(
            val descriptor: SerialDescriptor,
            val path: String,
            val disclosed: DisclosureBuilder?,
            val startsRecord: Boolean,
        ) {
            var elementPath: String = path
        }

        private val structures = ArrayList<Structure>()

        /** A `Partial` carries none of its model's annotations, so its model's descriptor stands in for it. */
        private var partialSource: SerialDescriptor? = null

        /** The record whose `_id` is being written. It takes the first Uuid encoded, however deeply wrapped. */
        private var awaitingId: DisclosureBuilder? = null

        override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
            if (value is Uuid) awaitingId?.let {
                it.id = value
                awaitingId = null
            }
            (serializer as? PartialSerializer<*>)?.let { partialSource = it.source.descriptor }
            super.encodeSerializableValue(serializer, value)
        }

        override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
            val effective = partialSource?.also { partialSource = null } ?: descriptor
            val parent = structures.lastOrNull()
            structures += when {
                effective.isAudited ->
                    Structure(effective, path = "", DisclosureBuilder(registry.modelId(effective.auditSerialName)), startsRecord = true)
                parent?.descriptor?.kind is PolymorphicKind ->
                    Structure(effective, "${parent.path}(${effective.auditSerialName})", parent.disclosed, startsRecord = false)
                else -> Structure(effective, parent?.elementPath ?: "", parent?.disclosed, startsRecord = false)
            }
            return this
        }

        override fun endStructure(descriptor: SerialDescriptor) {
            awaitingId = null
            val structure = structures.removeLast()
            if (!structure.startsRecord) return
            val builder = structure.disclosed!!
            found += Disclosure(
                builder.modelId,
                builder.id ?: throw IllegalStateException(
                    "An audited ${structure.descriptor.auditSerialName} reached a client without its _id, so the " +
                        "disclosure could not name which record was disclosed. A partial query on an audited " +
                        "model must include _id."
                ),
                builder.bits,
            )
        }

        override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
            awaitingId = null
            val structure = structures.last()
            val name = descriptor.getElementName(index)
            structure.elementPath = when (structure.descriptor.kind) {
                StructureKind.LIST -> "${structure.path}[]"
                StructureKind.MAP -> if (index % 2 == 0) "${structure.path}{key}" else "${structure.path}{}"
                is PolymorphicKind -> structure.path
                else -> if (structure.path.isEmpty()) name else "${structure.path}.$name"
            }
            if (structure.startsRecord && name == "_id") awaitingId = structure.disclosed
            return true
        }

        /** Sets the bit of the element being written, if it has one. */
        private fun disclose() {
            val structure = structures.lastOrNull() ?: return
            val builder = structure.disclosed ?: return
            registry.bitIndexOrNull(builder.modelId, structure.elementPath)?.let { builder.bits += it }
        }

        override fun encodeValue(value: Any) {}
        override fun encodeNull() {}
        override fun encodeBoolean(value: Boolean) { if (value) disclose() }
        override fun encodeByte(value: Byte) { if (value != 0.toByte()) disclose() }
        override fun encodeShort(value: Short) { if (value != 0.toShort()) disclose() }
        override fun encodeInt(value: Int) { if (value != 0) disclose() }
        override fun encodeLong(value: Long) { if (value != 0L) disclose() }
        override fun encodeFloat(value: Float) { if (value != 0f) disclose() }
        override fun encodeDouble(value: Double) { if (value != 0.0) disclose() }
        override fun encodeChar(value: Char) { if (value.code != 0) disclose() }
        override fun encodeString(value: String) { if (value.isNotEmpty()) disclose() }
        override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) = disclose()

        override fun <T> encodeSerializableElement(
            descriptor: SerialDescriptor,
            index: Int,
            serializer: SerializationStrategy<T>,
            value: T,
        ) {
            encodeElement(descriptor, index)
            if (value?.carriesAValue() == true) disclose()
            encodeSerializableValue(serializer, value)
        }

        override fun <T : Any> encodeNullableSerializableElement(
            descriptor: SerialDescriptor,
            index: Int,
            serializer: SerializationStrategy<T>,
            value: T?,
        ) {
            encodeElement(descriptor, index)
            if (value == null) return
            if (value.carriesAValue()) disclose()
            encodeSerializableValue(serializer, value)
        }

        private fun Any.carriesAValue(): Boolean = when (this) {
            is Collection<*> -> isNotEmpty()
            is Map<*, *> -> isNotEmpty()
            else -> true
        }
    }
}
