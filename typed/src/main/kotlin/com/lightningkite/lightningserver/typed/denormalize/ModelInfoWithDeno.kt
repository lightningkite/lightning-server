package com.lightningkite.lightningserver.typed.denormalize

import com.lightningkite.lightningserver.definition.StartupTask
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.AuthAccess
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.optics.IdentityLens
import com.lightningkite.services.database.optics.Lens
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.typed.getOrBadRequest
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.interceptCreate
import com.lightningkite.services.database.interceptModification
import com.lightningkite.services.database.interceptReplace
import com.lightningkite.services.database.vet

public interface ModelInfoWithDeno<USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> : ModelInfo<USER, T, ID> {
    public val denormalizations: List<Denormalization<T, *, *>>
}

/**
 * Adds denormalization support to a [ModelInfo]
 *
 * This is the primary entry point for defining models that denormalize data from referenced models.
 * Use this instead of the standard `modelInfo()` when you need to copy data from referenced models
 * to avoid expensive joins or enable efficient permission checks.
 *
 * **How denormalization works:**
 * - On create/replace: Fetches referenced models and copies specified fields
 * - On modify: Detects reference changes and updates denormalized fields
 * - On referenced model change: Automatically updates all referencing models (if listenForChanges = true)
 *
 * Example:
 * ```kotlin
 * object AnnouncementEndpoints : ServerBuilder() {
 *     private val info = Server.database.modelInfoWithDeno(
 *         auth = UserAuth.require(),
 *         permissions = { permissions(auth) },
 *         denormalizations = { announcement ->
 *             announcement.course.denormalizes(Course.info) {
 *                 // Watch for changes - updates when Course.submitter.courseProvider changes
 *                 announcement.courseProvider assign it.submitter.courseProvider
 *
 *                 // No change watching - only updates when announcement.course reference changes
 *                 announcement.courseTitle assignOnce it.title
 *             }
 *         }
 *     )
 * }
 * ```
 *
 * **Important:** Remember to mark denormalized fields as `cannotBeModified()` in your update restrictions:
 * ```kotlin
 * updateRestrictions = blacklistRestrictions {
 *     it.courseProvider.cannotBeModified()  // denormalized field
 * }
 * ```
 */
context(builder: ServerBuilder)
public fun <SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> ModelInfo<SUBJECT, T, ID>.withDenormalizations(
    denormalizations: DenormalizationBuilder<T>.(Lens<T, T>) -> Unit,
): ModelInfoWithDeno<SUBJECT, T, ID> =
    object : ModelInfoWithDeno<SUBJECT, T, ID>, ModelInfo<SUBJECT, T, ID> by this@withDenormalizations {
        override val denormalizations by lazy {  // lazy so that we don't run into initialization order issues when declaring modelInfo
            DenormalizationBuilder<T>().apply { denormalizations(IdentityLens(serializer)) }.denormalizations
        }

        context(server: ServerRuntime)
        private fun wrapWithDeno(table: Table<T>): Table<T> =
            this.denormalizations.fold(table) { t, deno -> t.denormalize(deno) }

        private val wraps = this@withDenormalizations

        context(server: ServerRuntime)
        override suspend fun table(auth: AuthAccess<SUBJECT>): Table<T> = wrapWithDeno(wraps.table(auth))

        context(server: ServerRuntime)
        override fun table(): Table<T> = wrapWithDeno(wraps.table())

        init {
            val modelInfo = this
            with(builder) {
                builder.path.path("$tableName-deno") bind StartupTask {
                    modelInfo.denormalizations
                        .filter { it.listenForChanges }
                        .takeUnless { it.isEmpty() }
                        ?.let { deno ->
                            val table = Runtime { baseTable() }
                            for (d in deno) d.listenForDenormalizations(table)
                        }
                }
            }
        }
    }


context(runtime: ServerRuntime)
private fun <Model : Any, Ref : HasId<RID>, RID : Comparable<RID>> Table<Model>.denormalize(
    calculation: Denormalization<Model, Ref, RID>,
): Table<Model> = when (calculation) {
    is Denormalization.RequiredRef<Model, Ref, RID> -> denormalizeImpl(calculation)
    is Denormalization.OptionalRef<Model, Ref, RID> -> denormalizeNullableImpl(calculation)
}

private fun <Model : Any, Ref : HasId<RID>, RID : Comparable<RID>> Denormalization<Model, Ref, RID>.listenForDenormalizations(
    table: Runtime<Table<Model>>,
) {
    when (this) {
        is Denormalization.RequiredRef<Model, Ref, RID> -> info.registerChangeListener { changes ->
            for ((old, new) in changes.changes) {
                if (old == null || new == null) continue

                val mod = denormalize(old, new)
                if (mod.isNothing) continue

                table().updateManyIgnoringResult(
                    reference.mapCondition(Condition.Equal(old._id)),
                    mod
                )
            }
        }

        is Denormalization.OptionalRef<Model, Ref, RID> -> info.registerChangeListener { changes ->
            for ((old, new) in changes.changes) {
                if (old == null) continue

                val mod = denormalize(old, new)
                val removeRef =
                    if (new == null) reference.mapModification(   // model is deleted, remove reference
                        Modification.Assign(null)
                    )
                    else null

                if (!mod.isNothing || removeRef != null) table().updateManyIgnoringResult(
                    reference.mapCondition(
                        Condition.Equal(new?._id ?: old._id)
                    ),
                    Modification.Chain(
                        listOfNotNull(removeRef, mod)
                    )
                )
            }
        }
    }
}

/**
 * Internal implementation that adds denormalization interceptors for required references.
 * Intercepts create, replace, and modification operations to automatically denormalize data.
 */
context(_: ServerRuntime)
private fun <Model : Any, Referenced : HasId<RID>, RID : Comparable<RID>> Table<Model>.denormalizeImpl(
    deno: Denormalization.RequiredRef<Model, Referenced, RID>,
): Table<Model> = this
    .interceptCreate { model ->
        val reference = deno.info.table().getOrBadRequest(deno.reference.get(model))
        deno.denormalize(reference).invoke(model)
    }
    .interceptReplace { model ->
        val reference = deno.info.table().getOrBadRequest(deno.reference.get(model))
        deno.denormalize(reference).invoke(model)
    }
    .interceptModification { incomingMod ->
        var updatedMod = incomingMod
        var newReference: RID? = null
        incomingMod.vet(deno.reference) {
            when (it) {
                is Modification.Assign -> {
                    newReference = it.value
                }

                else -> {}
            }
        }
        if (newReference != null) {
            val source = deno.info.table().getOrBadRequest(newReference)
            updatedMod = Modification.Chain(
                listOf(incomingMod, deno.denormalize(source))
            )
        }
        updatedMod
    }

/**
 * Internal implementation that adds denormalization interceptors for optional references.
 * Intercepts create, replace, and modification operations to automatically denormalize data.
 */
context(_: ServerRuntime)
private fun <Model : Any, Referenced : HasId<RID>, RID : Comparable<RID>> Table<Model>.denormalizeNullableImpl(
    deno: Denormalization.OptionalRef<Model, Referenced, RID>,
): Table<Model> = this
    .interceptCreate { model ->
        val reference = deno.reference.get(model)?.let { deno.info.table().getOrBadRequest(it) }
        deno.denormalize(reference).invoke(model)
    }
    .interceptReplace { model ->
        val reference = deno.reference.get(model)?.let { deno.info.table().getOrBadRequest(it) }
        deno.denormalize(reference).invoke(model)
    }
    .interceptModification { incomingMod ->
        var updatedMod = incomingMod
        var changed = false
        var newReference: RID? = null
        incomingMod.vet(deno.reference) {
            when (it) {
                is Modification.Assign -> {
                    changed = true
                    newReference = it.value
                }

                else -> {}
            }
        }
        if (changed) {
            val source = newReference?.let { deno.info.table().getOrBadRequest(it) }
            updatedMod = Modification.Chain(
                listOf(incomingMod, deno.denormalize(source))
            )
        }
        updatedMod
    }
