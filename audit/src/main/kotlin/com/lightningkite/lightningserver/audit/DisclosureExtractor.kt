@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.lightningserver.audit

import com.lightningkite.services.database.PartialSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/** One audited record that reached a client, and which of its fields carried a value. */
internal data class Disclosure(
    val modelId: Int,
    val recordId: Uuid,
    val bits: FieldBits,
)

/**
 * Finds the audited records inside a value by walking it with its own serializer.
 *
 * Using the serializer rather than reflection means what is observed is exactly what the client will
 * receive — through lists, maps, `Partial`s, wrappers, and sealed hierarchies alike — and that a
 * model cannot be disclosed through a shape the walk fails to understand.
 *
 * Safe to share across requests: the only mutable state is a cache of resolved bit indices, which is
 * a pure function of the registry.
 */
internal class DisclosureExtractor(private val registry: AuditRegistry) {
    private val plans = ConcurrentHashMap<PlanKey, PathPlan>()

    fun <T> extract(
        serializer: KSerializer<T>,
        value: T,
        serializersModule: SerializersModule = EmptySerializersModule(),
    ): List<Disclosure> {
        val found = ArrayList<Disclosure>()
        Walk(serializersModule, found).encodeSerializableValue(serializer, value)
        return found
    }

    /** Marks an element that is not itemised: it is walked, but sets no bit. */
    private val NO_BIT: Int get() = -1

    private data class PlanKey(val modelId: Int, val basePath: String, val descriptor: SerialDescriptor)

    /**
     * The resolved bit index and child path for every element of one descriptor at one position
     * inside one audited model.
     *
     * Cached because a list of ten thousand records enters the same descriptor at the same path ten
     * thousand times; without this, every field of every record would rebuild its path string and
     * hash it against the registry.
     */
    private class PathPlan(
        val bitIndexes: IntArray,
        val childPaths: Array<String>,
        val idElementIndex: Int,
    )

    private fun planFor(modelId: Int, basePath: String, descriptor: SerialDescriptor): PathPlan =
        plans.getOrPut(PlanKey(modelId, basePath, descriptor)) {
            val count = descriptor.elementsCount
            val childPaths = Array(count) { index ->
                val name = descriptor.getElementName(index)
                if (basePath.isEmpty()) name else "$basePath.$name"
            }
            PathPlan(
                bitIndexes = IntArray(count) { registry.bitIndexOrNull(modelId, childPaths[it]) ?: NO_BIT },
                childPaths = childPaths,
                idElementIndex = (0 until count).firstOrNull { descriptor.getElementName(it) == "_id" } ?: -1,
            )
        }

    /** The record being assembled for one audited instance. */
    private class RecordBuilder(val modelId: Int) {
        var bits: FieldBits = FieldBits.EMPTY
        var recordId: Uuid? = null
    }

    private class Frame(
        val descriptor: SerialDescriptor,
        val basePath: String,
        /** The audited record this frame's fields belong to, or null when outside any audited model. */
        val record: RecordBuilder?,
        /** Non-null exactly when this frame both belongs to a record and names its elements. */
        val plan: PathPlan?,
        /** True when this frame opened [record] and must emit it on close. */
        val ownsRecord: Boolean,
    ) {
        var currentIndex: Int = -1
    }

    private inner class Walk(
        override val serializersModule: SerializersModule,
        private val out: MutableList<Disclosure>,
    ) : AbstractEncoder() {
        private val frames = ArrayList<Frame>()

        /**
         * Set when the value about to be encoded is a `Partial`, whose descriptor is named for
         * `Partial` and carries none of the model's class annotations. The source descriptor stands
         * in, so a partial is treated as what it is: some fields of that model.
         */
        private var partialSource: SerialDescriptor? = null

        private val top: Frame? get() = frames.lastOrNull()

        override fun encodeValue(value: Any) {}

        override fun encodeNull() {}

        override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
            (serializer as? PartialSerializer<*>)?.let { partialSource = it.source.descriptor }
            super.encodeSerializableValue(serializer, value)
        }

        override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
            val effective = partialSource?.also { partialSource = null } ?: descriptor
            val parent = top
            val newRecord =
                if (effective.isAudited) RecordBuilder(registry.modelId(effective.auditSerialName)) else null
            val record = newRecord ?: parent?.record
            val basePath = if (newRecord != null) "" else parent.childPathFor(effective)
            val named = effective.kind == StructureKind.CLASS || effective.kind == StructureKind.OBJECT

            frames.add(
                Frame(
                    descriptor = effective,
                    basePath = basePath,
                    record = record,
                    plan = if (record != null && named) planFor(record.modelId, basePath, effective) else null,
                    ownsRecord = newRecord != null,
                )
            )
            return this
        }

        override fun endStructure(descriptor: SerialDescriptor) {
            val frame = frames.removeLast()
            if (!frame.ownsRecord) return
            val record = frame.record!!
            val recordId = record.recordId ?: throw IllegalStateException(
                "An audited ${frame.descriptor.auditSerialName} reached a client without its _id, so the " +
                    "disclosure could not name which record was disclosed. A partial query on an audited " +
                    "model must include _id."
            )
            out.add(Disclosure(record.modelId, recordId, record.bits))
        }

        override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
            top?.currentIndex = index
            return true
        }

        private fun discloseCurrent() {
            disclose(top?.currentIndex ?: return)
        }

        /** Records that the element at [index] of the current frame carried a value. */
        private fun disclose(index: Int) {
            val frame = top ?: return
            val plan = frame.plan ?: return
            if (index < 0 || index >= plan.bitIndexes.size) return
            val bit = plan.bitIndexes[index]
            if (bit == NO_BIT) return
            frame.record?.let { it.bits += bit }
        }

        /**
         * Where a nested structure sits, relative to the audited model containing it. Mirrors the
         * path rules the registry assigned bits under; see [auditFieldPaths].
         */
        private fun Frame?.childPathFor(child: SerialDescriptor): String {
            if (this == null) return ""
            return when (descriptor.kind) {
                StructureKind.LIST -> "$basePath[]"
                StructureKind.MAP -> "$basePath{}"
                is PolymorphicKind -> "$basePath(${child.auditSerialName})"
                else -> plan?.childPaths?.getOrNull(currentIndex) ?: basePath
            }
        }

        // Field presence is judged on the value, not on whether the format in use would have written
        // it: a default is a default whether or not a given encoder elides it. See section 5.5.
        //
        // AbstractEncoder routes every primitive element through encodeElement and then the bare
        // encodeX, and the element forms are final — so the index is remembered above and consumed
        // here. That also means a value whose serializer writes a primitive without opening a
        // structure of its own, such as Uuid, is attributed to the element that holds it, which is
        // what we want.
        override fun encodeBoolean(value: Boolean) {
            if (value) discloseCurrent()
        }

        override fun encodeByte(value: Byte) {
            if (value != 0.toByte()) discloseCurrent()
        }

        override fun encodeShort(value: Short) {
            if (value != 0.toShort()) discloseCurrent()
        }

        override fun encodeInt(value: Int) {
            if (value != 0) discloseCurrent()
        }

        override fun encodeLong(value: Long) {
            if (value != 0L) discloseCurrent()
        }

        override fun encodeFloat(value: Float) {
            if (value != 0f) discloseCurrent()
        }

        override fun encodeDouble(value: Double) {
            if (value != 0.0) discloseCurrent()
        }

        override fun encodeChar(value: Char) {
            if (value.code != 0) discloseCurrent()
        }

        override fun encodeString(value: String) {
            if (value.isNotEmpty()) discloseCurrent()
        }

        /** An enum has no natural zero, so holding any value of one is a disclosure. */
        override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
            discloseCurrent()
        }

        override fun <T> encodeSerializableElement(
            descriptor: SerialDescriptor,
            index: Int,
            serializer: SerializationStrategy<T>,
            value: T,
        ) {
            encodeElement(descriptor, index)
            captureId(index, value)
            if (value.carriesAValue()) disclose(index)
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
            captureId(index, value)
            if (value.carriesAValue()) disclose(index)
            encodeSerializableValue(serializer, value)
        }

        /** An empty collection is a default, so it is not a disclosure — see section 5.5. */
        private fun Any?.carriesAValue(): Boolean = when (this) {
            null -> false
            is Collection<*> -> isNotEmpty()
            is Map<*, *> -> isNotEmpty()
            else -> true
        }

        private fun captureId(index: Int, value: Any?) {
            val frame = top ?: return
            if (!frame.ownsRecord || index != frame.plan?.idElementIndex) return
            frame.record?.recordId = value as? Uuid
        }
    }
}
