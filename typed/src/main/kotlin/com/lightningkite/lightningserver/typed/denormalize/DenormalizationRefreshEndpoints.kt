package com.lightningkite.lightningserver.typed.denormalize

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.launch
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.pathing.arg1
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.lightningserver.typed.explicitApiHttpHandler
import com.lightningkite.lightningserver.typed.getOrBadRequest
import com.lightningkite.lightningserver.typed.getOrNotFound
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database._id
import com.lightningkite.services.database.all
import com.lightningkite.services.database.updateOneByIdIgnoringResult
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.builtins.serializer

private val logger = KotlinLogging.logger("com.lightningkite.lightningserver.typed.denormalize.DenormalizationRefreshEndpoints")

/** Caps how many ids [DenormalizationRefreshEndpoints.refreshAllDenormalizations] puts in a single update query. */
private const val UPDATE_BATCH = 1000

/**
 * Admin endpoints that recalculate the denormalized fields of a model created by [modelInfoWithDeno].
 *
 * Use them to repair rows whose denormalized fields went stale, for example after writes through
 * [ModelInfo.baseTable] or a migration. The endpoints take `includeSnapshots` as their input: fields written by
 * `snapshot` keep their values unless it is `true`.
 */
public class DenormalizationRefreshEndpoints<USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    private val info: ModelInfoWithDeno<USER, T, ID>
) : ServerBuilder() {
    // Claude: one per refresh, so refreshing many rows fetches each referenced row once.
    private class ReferencedRows {
        private val rows = HashMap<Pair<ModelInfo<*, *, *>, Any>, Any>()

        context(_: ServerRuntime)
        suspend fun <Ref : HasId<RID>, RID : Comparable<RID>> get(info: ModelInfo<*, Ref, RID>, id: RID): Ref {
            @Suppress("UNCHECKED_CAST")
            return rows.getOrPut(info to id) { info.table().getOrBadRequest(id) } as Ref
        }
    }

    context(_: ServerRuntime)
    private suspend fun <Ref, Input : Ref?> refreshedFields(
        calculations: List<Denormalization.Calculation<T, Ref, Input>>,
        ref: Input,
        includeSnapshots: Boolean,
    ): Modification<T> = Modification.Chain(
        calculations.filter { includeSnapshots || it.dependencies.isNotEmpty() }.map { it.calculation(ref) }
    )

    context(_: ServerRuntime)
    private suspend fun <Ref : HasId<RID>, RID : Comparable<RID>> Denormalization.RequiredRef<T, Ref, RID>.refreshedFields(
        model: T,
        rows: ReferencedRows,
        includeSnapshots: Boolean,
    ): Modification<T> {
        val id = reference.get(model) ?: return Modification.Nothing()
        return refreshedFields(calculations, rows.get(this.info, id), includeSnapshots)
    }

    context(_: ServerRuntime)
    private suspend fun <Ref : HasId<RID>, RID : Comparable<RID>> Denormalization.OptionalRef<T, Ref, RID>.refreshedFields(
        model: T,
        rows: ReferencedRows,
        includeSnapshots: Boolean,
    ): Modification<T> = refreshedFields(calculations, reference.get(model)?.let { rows.get(this.info, it) }, includeSnapshots)

    context(_: ServerRuntime)
    private suspend fun refresh(model: T, rows: ReferencedRows, includeSnapshots: Boolean): Modification<T> = Modification.Chain(
        info.denormalizations.filter { includeSnapshots || it.watchesReferencedModel }.map { deno ->
            val mod = when (deno) {
                is Denormalization.RequiredRef<T, *, *> -> deno.refreshedFields(model, rows, includeSnapshots)
                is Denormalization.OptionalRef<T, *, *> -> deno.refreshedFields(model, rows, includeSnapshots)
            }
            if (mod(model) == model) Modification.Nothing() else mod
        }
    )

    public val refreshDetail: ApiHttpHandler<PathSpec1<ID>, HasId<*>, Boolean, Modification<T>> =
        path.arg(PathSpec.Segment.Wildcard("id", info.idSerializer)).post bind explicitApiHttpHandler(
            summary = "Refresh Denormalizations",
            description = "Recalculates the denormalized fields on this ${info.tableName} from its references and returns the modification applied. The input is includeSnapshots: snapshot fields are kept unless it is true.",
            inputType = Boolean.serializer(),
            outputType = Modification.serializer(info.serializer),
            auth = AuthRequirement.IsAdmin,
            implementation = { includeSnapshots: Boolean ->
                val model = info.table().getOrNotFound(request.arg1)
                val mod = refresh(model, ReferencedRows(), includeSnapshots)
                if (!mod.isNothing) info.baseTableWithDenormalizations().updateOneByIdIgnoringResult(model._id, mod)
                mod
            }
        )

    /**
     * Recalculates the denormalized fields on every row and returns the modifications applied, keyed by id.
     *
     * Fields written by `snapshot` keep their values unless [includeSnapshots] is `true`. Rows that can't be refreshed, such as rows whose reference points at a missing model, are logged and skipped.
     */
    context(_: ServerRuntime)
    public suspend fun refreshAllDenormalizations(includeSnapshots: Boolean): Map<String, Modification<T>> {
        // todo: parallelize
        // Streamed rather than materialized: the rows themselves are discarded as we go and only the
        // modifications that are actually needed are retained (those are the return value anyway).
        // The write pass runs after the read finishes so updates never race the cursor feeding them.
        val rows = ReferencedRows()
        val mods = LinkedHashMap<ID, Modification<T>>()
        info.table().all().collect { model ->
            val mod = try {
                refresh(model, rows, includeSnapshots)
            } catch (e: BadRequestException) {
                logger.warn { "Skipped refreshing ${info.tableName} ${model._id}: ${e.message}" }
                return@collect
            }
            if (!mod.isNothing) mods[model._id] = mod
        }

        // Rows needing the identical modification go out in one write, so the change notifications
        // this produces scale with the number of distinct changes rather than the number of rows.
        val table = info.baseTableWithDenormalizations()
        for ((mod, ids) in mods.entries.groupBy({ it.value }, { it.key })) {
            for (idBatch in ids.chunked(UPDATE_BATCH)) {
                table.updateManyIgnoringResult(
                    Condition.OnField(info.serializer._id(), Condition.Inside(idBatch)),
                    mod
                )
            }
        }

        return mods.mapKeys { it.key.toString() }
    }

    /** Runs [refreshAllDenormalizations] in the background. */
    public val refreshAllTask: Task<Boolean> = path.path("refresh-all-task") bind Task { includeSnapshots: Boolean ->
        refreshAllDenormalizations(includeSnapshots)
    }

    public val refreshAll: ApiHttpHandler<PathSpec0, HasId<*>, Boolean, Unit> =
        path.path("refresh-all").post bind explicitApiHttpHandler(
            summary = "Refresh All Denormalizations",
            description = "Starts recalculating the denormalized fields on every ${info.tableName} in the background. The input is includeSnapshots: snapshot fields are kept unless it is true. Rows that can't be refreshed are logged and skipped.",
            inputType = Boolean.serializer(),
            outputType = Unit.serializer(),
            auth = AuthRequirement.IsAdmin,
            implementation = { includeSnapshots: Boolean ->
                refreshAllTask.launch(includeSnapshots)
            }
        )
}

// TODO: Api Recommendations
// Claude:
// - refreshAll runs as one Task with the default 5 minute timeout; a very large table needs the work split into batches of Tasks.
