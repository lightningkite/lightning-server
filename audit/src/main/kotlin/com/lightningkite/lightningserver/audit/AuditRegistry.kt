package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.concurrent.associate
import com.lightningkite.lightningserver.concurrent.groupBy
import com.lightningkite.lightningserver.definition.PreDeployTask
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.Engine
import com.lightningkite.lightningserver.runtime.engine
import com.lightningkite.lightningserver.typed.DatabaseTableRegistration
import com.lightningkite.lightningserver.typed.sdk.usedTypes
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.all
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor

public typealias SerialName = String

/**
 * The permanent meaning of one model id.
 *
 * Never deleted. A model that stops being served keeps its id so that historical
 * [AuditDisclosureRecord]s stay interpretable.
 *
 * @property _id The model's serial name. Serial names are used rather than table names because a
 *   disclosure is observed with a serializer in hand and nothing else — the table a value came from
 *   is not knowable at that point, and an audited model need not be a table at all.
 */
@GenerateDataClassPaths
@Serializable
public data class AuditModelRegistration(
    override val _id: SerialName,
    val modelId: ModelTypeId,
) : HasId<String>

/**
 * The permanent meaning of one bit.
 *
 * Never deleted, and never reassigned. A field that is renamed or removed simply stops being
 * written; its row remains, so old records keep resolving to the field they actually disclosed. A
 * rename therefore allocates a fresh bit, and the two bits are both correct — the old one for
 * records written before the rename, the new one for records written after.
 *
 * @property fieldPath Dotted path from the audited model's root — `ssn`, `address.street`,
 *   `phones[].number`. See [auditFieldPaths].
 */
@GenerateDataClassPaths
@Serializable
public data class AuditFieldRegistration(
    override val _id: String,
    val modelId: ModelTypeId,
    val fieldPath: String,
    val fieldId: ModelFieldId,
) : HasId<String>

/**
 * What every audited model id and field bit permanently means.
 *
 * Assigns ids and bits to every audited model this server serves in a pre-deploy task, and loads the
 * result as [assignments]. Include it in the server, or assignment never runs.
 */
public class AuditRegistry(
    private val models: DatabaseTableRegistration<AuditModelRegistration>,
    private val fields: DatabaseTableRegistration<AuditFieldRegistration>,
) : ServerBuilder() {
    private val logger = KotlinLogging.logger("com.lightningkite.lightningserver.audit.AuditRegistry")

    /**
     * The assignments in force for this process.
     *
     * Assignments only ever grow, and only during a deploy, so a snapshot taken at first use stays
     * correct for the life of the process.
     */
    public class Assignments internal constructor(
        internal val modelIds: Map<SerialName, ModelTypeId>,
        internal val bitIndices: Map<ModelTypeId, Map<String, ModelFieldId>>,
    ) {
        public fun modelIdOrNull(descriptor: SerialDescriptor): ModelTypeId? = modelIds[descriptor.auditSerialName]

        public fun modelType(descriptor: SerialDescriptor): ModelTypeId {
            val serialName = descriptor.auditSerialName
            return modelIds[serialName] ?: throw IllegalStateException(
                // Claude: an empty registry means assignment never ran, which needs a different fix.
                if (modelIds.isEmpty()) "The audit registry is empty, so \"$serialName\" — and every other " +
                    "audited model — has no entry. Bit assignment never ran. Check that the AuditRegistry is " +
                    "included in the server definition, not merely constructed, and that pre-deploy tasks " +
                    "run before serving."
                else
                    "Audited model \"$serialName\" has no registry entry, so nothing about it can be recorded. " +
                        "The deploy-time scan registers the audited models that endpoints use, so either no endpoint " +
                        "uses it or it is only reached through a serializer the scan cannot resolve statically."
            )
        }

        internal fun bitIndexOrNull(modelId: ModelTypeId, fieldPath: String): ModelFieldId? = bitIndices[modelId]?.get(fieldPath)

        public fun fieldId(modelId: ModelTypeId, fieldPath: String): ModelFieldId =
            bitIndexOrNull(modelId, fieldPath) ?: throw IllegalStateException(
                "Field \"$fieldPath\" of model ${modelId.raw} has no registered bit index."
            )

        /** Every registered field of a model, as path to bit index. */
        public fun fields(modelId: ModelTypeId): Map<String, ModelFieldId> = bitIndices[modelId].orEmpty()
    }

    /** Loaded on first use and cached for the life of the process. */
    public val assignments: RuntimeDeferred<Assignments> = RuntimeDeferred.Cached { load() }

    // Claude: pre-deploy rather than startup, so instances never race to allocate the same index.
    private val assignTask: PreDeployTask = path.path("assign-audit-bits") bind PreDeployTask(
        dependencies = { listOf(models.preDeployTask, fields.preDeployTask) },
    ) {
        register(engine.usedTypes().map { it.descriptor }.filter { it.isAudited })
    }

    context(server: Engine)
    internal suspend fun load(): Assignments = Assignments(
        modelIds = models().all().associate { it._id to it.modelId },
        bitIndices = fields().all()
            .groupBy { it.modelId }
            .mapValues { (_, rows) -> rows.associate { it.fieldPath to it.fieldId } },
    )

    /**
     * Assigns permanent ids and bit indices to anything in [audited] that does not have them yet.
     *
     * Append-only and convergent: existing assignments are never changed, so re-running it on every
     * deploy is a no-op.
     */
    context(server: Engine)
    internal suspend fun register(audited: List<SerialDescriptor>) {
        val existing = load()

        val modelIds = existing.modelIds.toMutableMap()
        var nextModelId = (existing.modelIds.values.maxOfOrNull { it.raw } ?: -1) + 1

        val newModels = audited.map { it.auditSerialName }.filter { it !in modelIds }.map { serialName ->
            AuditModelRegistration(_id = serialName, modelId = ModelTypeId(nextModelId++))
                .also { modelIds[serialName] = it.modelId }
        }
        if (newModels.isNotEmpty()) models().insert(newModels)

        val newFields = audited.flatMap { descriptor ->
            val serialName = descriptor.auditSerialName
            val modelId = modelIds.getValue(serialName)
            val assigned = existing.fields(modelId)
            var nextBit = (assigned.maxOfOrNull { it.value.raw } ?: -1) + 1

            descriptor.auditFieldPaths().filter { it !in assigned }.map { path ->
                if (nextBit >= FieldIdentifierSet.CAPACITY) throw IllegalStateException(
                    "Audited model \"$serialName\" has run out of field bits at \"$path\": " +
                        "${assigned.size} of ${FieldIdentifierSet.CAPACITY} are already assigned. Indices are never " +
                        "reused, so renamed and removed fields still hold theirs. Remove @Audited from " +
                        "properties that do not need itemising, or make a nested entity type an AuditedModel so " +
                        "it becomes its own disclosure record."
                )
                AuditFieldRegistration(
                    _id = "${modelId.raw}/$path",
                    modelId = modelId,
                    fieldPath = path,
                    fieldId = ModelFieldId(nextBit++),
                )
            }
        }
        if (newFields.isNotEmpty()) fields().insert(newFields)

        // Claude: fresh reads, since the cached [assignments] would never see this deploy's additions.
        warnOnLowCapacity(load())
    }

    /**
     * Warns while there is still room to act.
     *
     * Running out of bits fails a deploy, and finding out then is the worst time to find out. Bits are
     * consumed permanently, so a model creeps towards the ceiling over its life rather than jumping.
     */
    private fun warnOnLowCapacity(assignments: Assignments) {
        for ((serialName, modelId) in assignments.modelIds) {
            val used = assignments.fields(modelId).size
            if (used < FieldIdentifierSet.CAPACITY * 3 / 4) continue
            logger.warn {
                "Audited model \"$serialName\" has used $used of ${FieldIdentifierSet.CAPACITY} " +
                    "field bits. Indices are never reused, so this only grows."
            }
        }
    }
}
