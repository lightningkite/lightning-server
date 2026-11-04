package com.lightningkite.lightningserver.typed.denormalize

import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.pathing.arg1
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.explicitApiHttpHandler
import com.lightningkite.lightningserver.typed.getOrNotFound
import com.lightningkite.lightningserver.typed.getOrThrow
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database._id
import com.lightningkite.services.database.all
import com.lightningkite.services.database.updateOneByIdIgnoringResult
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer


/** Caps how many ids [RefreshDenoEndpoints.refreshAllDenormalizations] puts in a single update query. */
private const val UPDATE_BATCH = 1000

public class RefreshDenoEndpoints<USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    private val info: ModelInfoWithDeno<USER, T, ID>
) : ServerBuilder() {
    context(_: ServerRuntime)
    private suspend fun <Ref : HasId<RID>, RID : Comparable<RID>> Denormalization<T, Ref, RID>.modification(
        model: T
    ): Modification<T> = when (val deno = this) {
        is Denormalization.OptionalRef<T, Ref, RID> -> {
            val ref = deno.reference.get(model)
            val referenced = ref?.let { deno.info.table().getOrThrow(it) }
            val mod = deno.denormalize(referenced)

            if (mod(model) == model) Modification.Nothing()
            else mod
        }

        is Denormalization.RequiredRef<T, Ref, RID> -> {
            val referenced = deno.info.table().getOrThrow(deno.reference.get(model))
            val mod = deno.denormalize(referenced)

            if (mod(model) == model) Modification.Nothing()
            else mod
        }
    }

    public val refreshDetail: ApiHttpHandler<PathSpec1<ID>, HasId<*>, Unit, Modification<T>> =
        path.arg(PathSpec.Segment.Wildcard("id", info.idSerializer)).post bind explicitApiHttpHandler(
            summary = "Refresh Denormalizations",
            description = "Recalculates every denormalized field on this ${info.tableName} from its references and returns the modification applied.",
            inputType = Unit.serializer(),
            outputType = Modification.serializer(info.serializer),
            auth = AuthRequirement.IsAdmin,
            implementation = {
                val model = info.table().getOrNotFound(request.arg1)
                val mod = Modification.Chain(info.denormalizations.map { it.modification(model) })
                if (!mod.isNothing) info.baseTableWithDenormalizations().updateOneByIdIgnoringResult(model._id, mod)
                mod
            }
        )

    context(_: ServerRuntime)
    public suspend fun refreshAllDenormalizations(): Map<String, Modification<T>> {
        // todo: should re-use reference ids where possible and parallelize
        // Streamed rather than materialized: the rows themselves are discarded as we go and only the
        // modifications that are actually needed are retained (those are the return value anyway).
        // The write pass runs after the read finishes so updates never race the cursor feeding them.
        val mods = LinkedHashMap<ID, Modification<T>>()
        info.table().all().collect { model ->
            val mod = Modification.Chain(info.denormalizations.map { it.modification(model) })
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

    public val refreshAll: ApiHttpHandler<PathSpec0, HasId<*>, Unit, Map<String, Modification<T>>> =
        path.path("refresh-all").post bind explicitApiHttpHandler(
            summary = "Refresh Denormalizations",
            inputType = Unit.serializer(),
            outputType = MapSerializer(String.serializer(), Modification.serializer(info.serializer)),
            auth = AuthRequirement.IsAdmin,
            implementation = {
                refreshAllDenormalizations()
            }
        )
}
