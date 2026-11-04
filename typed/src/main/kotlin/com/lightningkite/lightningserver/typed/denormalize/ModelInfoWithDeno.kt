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
import com.lightningkite.lightningserver.typed.getOrBadRequest
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.interceptCreate
import com.lightningkite.services.database.interceptModification
import com.lightningkite.services.database.interceptReplace
import java.util.concurrent.atomic.AtomicBoolean

public interface ModelInfoWithDeno<USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> : ModelInfo<USER, T, ID> {
    public val denormalizations: List<Denormalization<T, *, *>>

    /**
     * Returns [baseTable] with this model's [denormalizations] applied, so a write that changes a reference also
     * recalculates the fields denormalized from it.
     *
     * Like [baseTable], writes through this table skip permissions and signals.
     */
    context(server: ServerRuntime)
    public fun baseTableWithDenormalizations(): Table<T>
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
 * Denormalizations read from the source of truth only. Reading a field the referenced model marks `@Denormalized`
 * throws when the denormalizations are built. To reach a model two references away, denormalize the intermediate
 * reference onto this model and denormalize from that:
 * ```kotlin
 * a.b.denormalizes(B.info) { b -> a.c bind b.c }
 * a.c.denormalizes(C.info) { c -> a.cName bind c.name }
 * ```
 *
 * Fields written by `bind` or `set` must be marked `@Denormalized`.
 *
 * Example:
 * ```kotlin
 * val announcementInfo = Server.database.modelInfo<User, Announcement, Announcement.ID>(
 *     auth = UserAuth.require(),
 *     tableName = "Announcement",
 *     permissions = { permissions(auth) },
 * ).withDenormalizations { announcement ->
 *     announcement.course.denormalizes(CourseEndpoints.info) { course ->
 *         // Kept in sync: updates whenever Course.providerName changes
 *         announcement.courseProvider bind course.providerName
 *
 *         // Not watched: only updates when announcement.course changes
 *         announcement.courseTitle set course.title
 *     }
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
        override val denormalizations: List<Denormalization<T, *, *>> by lazy {  // lazy so that we don't run into initialization order issues when declaring modelInfo
            DenormalizationBuilder<T>().apply { denormalizations(IdentityLens(serializer)) }.denormalizations
        }

        context(server: ServerRuntime)
        private fun wrapWithDeno(table: Table<T>): Table<T> =
            // Claude: foldRight so the first declared denormalization intercepts first; later ones read the references it sets.
            this.denormalizations.foldRight(table) { deno, t ->
                when (deno) {
                    is Denormalization.RequiredRef<T, *, *> -> t.denormalizeImpl(deno)
                    is Denormalization.OptionalRef<T, *, *> -> t.denormalizeNullableImpl(deno)
                }
            }

        private val wraps = this@withDenormalizations

        context(server: ServerRuntime)
        override fun baseTableWithDenormalizations(): Table<T> = wrapWithDeno(baseTable())

        private val listening = AtomicBoolean(false)

        context(server: ServerRuntime)
        override suspend fun table(auth: AuthAccess<SUBJECT>): Table<T> = wrapWithDeno(wraps.table(auth))

        context(server: ServerRuntime)
        override fun table(): Table<T> = wrapWithDeno(wraps.table())

        init {
            val modelInfo = this
            with(builder) {
                builder.path.path("$tableName-deno") bind StartupTask {
                    // Claude: listeners live on the ModelInfo, which can outlive the engine, so a second engine in the same process (as in tests) must not add them again.
                    if (!listening.compareAndSet(false, true)) return@StartupTask
                    for (d in modelInfo.denormalizations) {
                        if (d.listenForChanges) d.listenForDenormalizations { modelInfo.baseTableWithDenormalizations() }
                    }
                }
            }
        }
    }


private fun <Model : Any, Ref : HasId<RID>, RID : Comparable<RID>> Denormalization<Model, Ref, RID>.listenForDenormalizations(
    table: context(ServerRuntime) () -> Table<Model>,
) {
    info.registerChangeListener { changes ->
        for ((old, new) in changes.changes) {
            if (old != null) updateReferencing(table(), old, new)
        }
    }
}

context(_: ServerRuntime)
private suspend fun <Model : Any, Ref : HasId<RID>, RID : Comparable<RID>> Denormalization<Model, Ref, RID>.updateReferencing(
    table: Table<Model>,
    old: Ref,
    new: Ref?,
) {
    when (this) {
        is Denormalization.RequiredRef<Model, Ref, RID> -> {
            if (new == null) return
            val mod = denormalize(old, new)
            if (mod.isNothing) return
            table.updateManyIgnoringResult(reference.mapCondition(Condition.Equal(old._id)), mod)
        }

        is Denormalization.OptionalRef<Model, Ref, RID> -> {
            val mod = denormalize(old, new)
            val removeRef =
                if (new == null) reference.mapModification(   // model is deleted, remove reference
                    Modification.Assign(null)
                )
                else null

            if (!mod.isNothing || removeRef != null) table.updateManyIgnoringResult(
                reference.mapCondition(Condition.Equal(old._id)),
                Modification.Chain(listOfNotNull(removeRef, mod))
            )
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
        incomingMod.vetNullable(deno.reference) {
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
