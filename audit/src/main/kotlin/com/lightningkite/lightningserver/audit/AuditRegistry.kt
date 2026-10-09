package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.definition.PreDeployTask
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.Engine
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.ApiWebSocketHandler
import com.lightningkite.lightningserver.typed.DatabaseTableRegistration
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.all
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.serializer
import kotlin.uuid.Uuid

public typealias SerialName = String

/**
 * The permanent meaning of one model id.
 *
 * Never deleted. A model that stops being served keeps its id so that historical
 * [DisclosureRecord]s stay interpretable.
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
        private val modelIds: Map<SerialName, ModelTypeId>,
        private val bitIndices: Map<ModelTypeId, Map<String, ModelFieldId>>,
    ) {
        /**
         * The permanent id of an audited model.
         *
         * Throws when the model was never registered, which fails the request that disclosed it. That
         * is deliberate: a disclosure that cannot be recorded must not happen. It means a model reached
         * a client through a path the deploy-time scan could not see — an open-polymorphic or
         * contextual serializer — and the fix is to make that model reachable from an endpoint.
         */
        public fun modelId(serialName: SerialName): ModelTypeId = modelIds[serialName] ?: throw IllegalStateException(
            // Claude: an empty registry means assignment never ran, which needs a different fix.
            if (modelIds.isEmpty()) "The audit registry is empty, so \"$serialName\" — and every other " +
                "audited model — has no entry. Bit assignment never ran. Check that AuditCore is " +
                "included in the server definition, not merely constructed, and that pre-deploy tasks " +
                "run before serving."
            else
                "Audited model \"$serialName\" has no registry entry, so its disclosure cannot be recorded. " +
                    "It reached a client through a serializer the deploy-time scan could not resolve statically."
        )

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
        assign(auditedModelsOnServer())
    }

    context(server: Engine)
    internal suspend fun load(): Assignments = Assignments(
        modelIds = models().all().toList().associate { it._id to it.modelId },
        bitIndices = fields().all().toList()
            .groupBy { it.modelId }
            .mapValues { (_, rows) -> rows.associate { it.fieldPath to it.fieldId } },
    )

    /**
     * Assigns permanent ids and bit indices to anything in [audited] that does not have them yet.
     *
     * Append-only and convergent: existing assignments are never changed, so re-running it on every
     * deploy is a no-op.
     *
     * @param audited Audited models, as serial name to descriptor.
     */
    context(server: Engine)
    internal suspend fun assign(audited: Map<SerialName, SerialDescriptor>) {
        audited.forEach { (serialName, descriptor) -> requireUuidKeyed(serialName, descriptor) }

        val existingModels = models().all().toList()
        val modelIds = existingModels.associate { it._id to it.modelId }.toMutableMap()
        var nextModelId = (existingModels.maxOfOrNull { it.modelId.raw } ?: -1) + 1

        val newModels = audited.keys.filter { it !in modelIds }.map { serialName ->
            AuditModelRegistration(_id = serialName, modelId = ModelTypeId(nextModelId++))
                .also { modelIds[serialName] = it.modelId }
        }
        if (newModels.isNotEmpty()) models().insert(newModels)

        val existingFields = fields().all().toList().groupBy { it.modelId }
        val newFields = audited.flatMap { (serialName, descriptor) ->
            val modelId = modelIds.getValue(serialName)
            val assigned = existingFields[modelId].orEmpty()
            val byPath = assigned.associate { it.fieldPath to it.fieldId }
            var nextBit = (assigned.maxOfOrNull { it.fieldId.raw } ?: -1) + 1

            descriptor.auditFieldPaths().filter { it !in byPath }.map { path ->
                if (nextBit >= FieldIdentifierSet.CAPACITY) throw IllegalStateException(
                    "Audited model \"$serialName\" has run out of field bits at \"$path\": " +
                        "${assigned.size} of ${FieldIdentifierSet.CAPACITY} are already assigned. Indices are never " +
                        "reused, so renamed and removed fields still hold theirs. Remove @Audited from " +
                        "properties that do not need itemising, or mark a nested entity type @Audited so " +
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

        warnOnLowCapacity(modelIds, existingFields, newFields)
    }

    /**
     * Every audited model this server can disclose, found by walking the serializers its endpoints
     * declare.
     *
     * Endpoints only, deliberately: auditing keys off serializers, never tables, because a disclosure
     * is observed with a serializer in hand and nothing else. A model no endpoint's serializer reaches
     * gets no id, and disclosing it fails the request — see [Assignments.modelId].
     */
    context(server: Engine)
    private fun auditedModelsOnServer(): Map<SerialName, SerialDescriptor> = buildMap {
        for (endpoints in server.server.endpoints.values) {
            for (handler in endpoints.http.values) {
                if (handler !is ApiHttpHandler<*, *, *, *>) continue
                putAll(handler.inputType.descriptor.auditedModels())
                putAll(handler.outputType.descriptor.auditedModels())
            }
            val socket = endpoints.webSocket
            if (socket is ApiWebSocketHandler<*, *, *, *, *>) {
                putAll(socket.inputType.descriptor.auditedModels())
                putAll(socket.outputType.descriptor.auditedModels())
            }
        }
    }

    /**
     * Warns while there is still room to act.
     *
     * Running out of bits fails a deploy, and finding out then is the worst time to find out. Bits are
     * consumed permanently, so a model creeps towards the ceiling over its life rather than jumping.
     */
    private fun warnOnLowCapacity(
        modelIds: Map<SerialName, ModelTypeId>,
        existing: Map<ModelTypeId, List<AuditFieldRegistration>>,
        added: List<AuditFieldRegistration>,
    ) {
        val total = existing.mapValues { it.value.size }.toMutableMap()
        added.groupBy { it.modelId }.forEach { (modelId, rows) -> total[modelId] = total.getOrElse(modelId) { 0 } + rows.size }
        val names = modelIds.entries.associate { it.value to it.key }
        total.filter { it.value >= FieldIdentifierSet.CAPACITY * 3 / 4 }.forEach { (modelId, used) ->
            logger.warn {
                "Audited model \"${names[modelId] ?: modelId.raw}\" has used $used of ${FieldIdentifierSet.CAPACITY} " +
                    "field bits. Indices are never reused, so this only grows."
            }
        }
    }

    /**
     * An audited model must be keyed by a `Uuid`, or by value classes wrapping one.
     *
     * Without an `_id` a disclosure record could not say *which* record was disclosed. Requiring that id
     * to be a `Uuid` is a storage decision: there is one disclosure row per record disclosed, so the
     * identifier column is written more than anything else in the system and needs to stay sixteen
     * bytes wide rather than a string a backend will index poorly.
     */
    private fun requireUuidKeyed(serialName: SerialName, descriptor: SerialDescriptor) {
        val idIndex = (0 until descriptor.elementsCount).firstOrNull { descriptor.getElementName(it) == "_id" }
            ?: throw IllegalStateException(
                "Audited model \"$serialName\" has no _id field, so a disclosure record could not say " +
                    "which record was disclosed. Give it an _id, or drop @Audited."
            )
        // Claude: a value class around a Uuid, such as a TypedId, still stores sixteen bytes.
        var idDescriptor = descriptor.getElementDescriptor(idIndex)
        while (idDescriptor.isInline) idDescriptor = idDescriptor.getElementDescriptor(0)
        if (idDescriptor.auditSerialName != serializer<Uuid>().descriptor.serialName) throw IllegalStateException(
            "Audited model \"$serialName\" is keyed by ${descriptor.getElementDescriptor(idIndex).auditSerialName}, " +
                "but auditing requires a Uuid _id, or a value class wrapping one. " +
                "One disclosure row is written per record disclosed, so the id column has to stay " +
                "sixteen bytes wide. Re-key the model, or drop @Audited."
        )
    }
}
