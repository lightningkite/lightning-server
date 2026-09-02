package com.lightningkite.lightningserver.audit

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.Table
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger("com.lightningkite.lightningserver.audit.AuditRegistry")

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
    override val _id: String,
    val modelId: Int,
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
    val modelId: Int,
    val fieldPath: String,
    val bitIndex: Int,
) : HasId<String>

/**
 * The bit assignments in force for this process, loaded once from the registry tables.
 *
 * Assignments only ever grow, and only during a deploy, so a snapshot taken at first use stays
 * correct for the life of the process.
 */
public class AuditRegistry internal constructor(
    private val modelIds: Map<String, Int>,
    private val bitIndices: Map<Int, Map<String, Int>>,
) {
    /**
     * The model's permanent id, or null when it is not audited.
     *
     * Unlike [modelId] this does not throw. Currently unused — the data access log gates on the
     * `@Audited` annotation and then resolves through [modelId], so that an audited model missing
     * from the registry fails rather than going unrecorded.
     */
    internal fun modelIdOrNull(serialName: String): Int? = modelIds[serialName]

    /**
     * The permanent id of an audited model.
     *
     * Throws when the model was never registered, which fails the request that disclosed it. That is
     * deliberate: a disclosure that cannot be recorded must not happen. It means a model reached a
     * client through a path the deploy-time scan could not see — an open-polymorphic or contextual
     * serializer — and the fix is to make that model reachable from a registered table or endpoint.
     */
    public fun modelId(serialName: String): Int = modelIdOrNull(serialName) ?: throw IllegalStateException(
        "Audited model \"$serialName\" has no registry entry, so its disclosure cannot be recorded. " +
            "It reached a client through a serializer the deploy-time scan could not resolve statically."
    )

    internal fun bitIndexOrNull(modelId: Int, fieldPath: String): Int? = bitIndices[modelId]?.get(fieldPath)

    public fun bitIndex(modelId: Int, fieldPath: String): Int =
        bitIndexOrNull(modelId, fieldPath) ?: throw IllegalStateException(
            "Field \"$fieldPath\" of model $modelId has no registered bit index."
        )

    /** Every registered field of a model, as path to bit index. */
    public fun fields(modelId: Int): Map<String, Int> = bitIndices[modelId].orEmpty()
}

/** Reads the current assignments out of the registry tables. */
internal suspend fun loadAuditRegistry(
    models: Table<AuditModelRegistration>,
    fields: Table<AuditFieldRegistration>,
): AuditRegistry = AuditRegistry(
    modelIds = models.find(Condition.Always).toList().associate { it._id to it.modelId },
    bitIndices = fields.find(Condition.Always).toList()
        .groupBy { it.modelId }
        .mapValues { (_, rows) -> rows.associate { it.fieldPath to it.bitIndex } },
)

/**
 * Assigns permanent ids and bit indices to anything audited that does not have them yet.
 *
 * Append-only and convergent: existing assignments are never changed, so this is safe to re-run on
 * every deploy, which is exactly how pre-deploy tasks behave.
 *
 * @param audited Every audited model found on the server, as serial name to descriptor.
 */
internal suspend fun reconcileAuditRegistry(
    models: Table<AuditModelRegistration>,
    fields: Table<AuditFieldRegistration>,
    audited: Map<String, SerialDescriptor>,
) {
    audited.forEach { (serialName, descriptor) -> descriptor.requireUuidKeyed(serialName) }

    val existingModels = models.find(Condition.Always).toList()
    val modelIds = existingModels.associate { it._id to it.modelId }.toMutableMap()
    var nextModelId = (existingModels.maxOfOrNull { it.modelId } ?: -1) + 1

    val newModels = audited.keys.filter { it !in modelIds }.map { serialName ->
        AuditModelRegistration(_id = serialName, modelId = nextModelId++)
            .also { modelIds[serialName] = it.modelId }
    }
    if (newModels.isNotEmpty()) models.insert(newModels)

    val existingFields = fields.find(Condition.Always).toList().groupBy { it.modelId }
    val newFields = audited.flatMap { (serialName, descriptor) ->
        val modelId = modelIds.getValue(serialName)
        val assigned = existingFields[modelId].orEmpty()
        val byPath = assigned.associate { it.fieldPath to it.bitIndex }
        var nextBit = (assigned.maxOfOrNull { it.bitIndex } ?: -1) + 1

        descriptor.auditFieldPaths().filter { it !in byPath }.map { path ->
            if (nextBit >= FieldBits.CAPACITY) throw IllegalStateException(
                "Audited model \"$serialName\" has run out of field bits at \"$path\": " +
                    "${assigned.size} of ${FieldBits.CAPACITY} are already assigned. Indices are never " +
                    "reused, so renamed and removed fields still hold theirs. Remove @Audited from " +
                    "properties that do not need itemising, or mark a nested entity type @Audited so " +
                    "it becomes its own disclosure record."
            )
            AuditFieldRegistration(
                _id = "$modelId/$path",
                modelId = modelId,
                fieldPath = path,
                bitIndex = nextBit++,
            )
        }
    }
    if (newFields.isNotEmpty()) fields.insert(newFields)

    warnOnLowCapacity(modelIds, existingFields, newFields)
}

/**
 * Warns while there is still room to act.
 *
 * Running out of bits fails a deploy, and finding out then is the worst time to find out. Bits are
 * consumed permanently, so a model creeps towards the ceiling over its life rather than jumping.
 */
private fun warnOnLowCapacity(
    modelIds: Map<String, Int>,
    existing: Map<Int, List<AuditFieldRegistration>>,
    added: List<AuditFieldRegistration>,
) {
    val total = existing.mapValues { it.value.size }.toMutableMap()
    added.groupBy { it.modelId }.forEach { (modelId, rows) -> total[modelId] = total.getOrElse(modelId) { 0 } + rows.size }
    val names = modelIds.entries.associate { it.value to it.key }
    total.filter { it.value >= FieldBits.CAPACITY * 3 / 4 }.forEach { (modelId, used) ->
        logger.warn {
            "Audited model \"${names[modelId] ?: modelId}\" has used $used of ${FieldBits.CAPACITY} " +
                "field bits. Indices are never reused, so this only grows."
        }
    }
}

/**
 * An audited model must be keyed by a `Uuid`.
 *
 * Without an `_id` a disclosure record could not say *which* record was disclosed. Requiring that id
 * to be a `Uuid` is a storage decision: there is one disclosure row per record disclosed, so the
 * identifier column is written more than anything else in the system and needs to stay sixteen
 * bytes wide rather than a string a backend will index poorly.
 */
private fun SerialDescriptor.requireUuidKeyed(serialName: String) {
    val idIndex = (0 until elementsCount).firstOrNull { getElementName(it) == "_id" }
        ?: throw IllegalStateException(
            "Audited model \"$serialName\" has no _id field, so a disclosure record could not say " +
                "which record was disclosed. Give it an _id, or drop @Audited."
        )
    val idType = getElementDescriptor(idIndex).auditSerialName
    if (idType != UUID_SERIAL_NAME) throw IllegalStateException(
        "Audited model \"$serialName\" is keyed by $idType, but auditing requires a Uuid _id. " +
            "One disclosure row is written per record disclosed, so the id column has to stay " +
            "sixteen bytes wide. Re-key the model, or drop @Audited."
    )
}

private val UUID_SERIAL_NAME = serializer<Uuid>().descriptor.serialName
