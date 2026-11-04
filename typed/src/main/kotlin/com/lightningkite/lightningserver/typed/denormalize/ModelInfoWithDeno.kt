package com.lightningkite.lightningserver.typed.denormalize

import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.auth.Subscope
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.StartupTask
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.AuthAccess
import com.lightningkite.lightningserver.serialization.serializerOrContextual
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.lightningserver.typed.explicitModelInfo
import com.lightningkite.lightningserver.typed.modelInfo
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.optics.IdentityLens
import com.lightningkite.services.database.optics.Lens
import com.lightningkite.lightningserver.typed.getOrBadRequest
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.ModelPermissions
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.interceptCreate
import com.lightningkite.services.database.interceptModification
import com.lightningkite.services.database.interceptReplace
import kotlinx.serialization.KSerializer
import java.util.concurrent.atomic.AtomicBoolean

public interface ModelInfoWithDeno<USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> : ModelInfo<USER, T, ID> {
    public val denormalizations: List<Denormalization<T, *, *, *>>

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
 * Creates a [ModelInfo] like [modelInfo] whose table also copies data from the models it references.
 *
 * Each denormalization is declared on a reference field with `denormalizes`:
 * - On create and replace, it fetches the referenced model and fills in the denormalized fields.
 * - On a modification that changes the reference, it recalculates them.
 * - With `watch`, a change to the referenced model updates every model that references it. With `snapshot`, it doesn't.
 *
 * Denormalizations read from the source of truth only. Reading a field the referenced model marks `@Denormalized`
 * throws when the denormalizations are built. To reach a model two references away, denormalize the intermediate
 * reference onto this model and denormalize from that:
 * ```kotlin
 * a.b.denormalizes(B.info) { b -> a.c watch b.c }
 * a.c.denormalizes(C.info) { c -> a.cName watch c.name }
 * ```
 *
 * Fields written by the infix `watch` or `snapshot` must be marked `@Denormalized`. Mark them `cannotBeModified()` in the update
 * restrictions too, so callers can't write them; the denormalizations still can. The caller chooses the reference,
 * so a denormalized field shows the referenced model's value to anyone who can read this model.
 *
 * Example:
 * ```kotlin
 * val announcementInfo = Server.database.modelInfoWithDeno<User, Announcement, Announcement.ID>(
 *     auth = UserAuth.require(),
 *     tableName = "Announcement",
 *     permissions = {
 *         permissions(auth).withAdditionalUpdateRestrictions {
 *             it.courseProvider.cannotBeModified()
 *             it.courseTitle.cannotBeModified()
 *         }
 *     },
 * ) { announcement ->
 *     announcement.course.denormalizes(CourseEndpoints.info) { course ->
 *         // Kept in sync: updates whenever Course.providerName changes
 *         announcement.courseProvider watch course.providerName
 *
 *         // Copied when announcement.course is set; later changes to the course are not copied
 *         announcement.courseTitle snapshot course.title
 *     }
 * }
 * ```
 */
context(builder: ServerBuilder)
public inline fun <reified USER : HasId<*>?, reified T : HasId<ID>, reified ID : Comparable<ID>> Runtime<Database>.modelInfoWithDeno(
    auth: AuthRequirement<USER>,
    tableName: String,
    subscope: Subscope? = Subscope(tableName.lowercase()),
    noinline signals: context(ServerRuntime) (Table<T>) -> Table<T> = { it },
    noinline log: context(ServerRuntime) AuthAccess<USER>?.(Table<T>) -> Table<T> = { it },
    noinline systemAccess: context(ServerRuntime) (Table<T>) -> Table<T> = { it },
    noinline postPermissionsForUser: suspend context(ServerRuntime) AuthAccess<USER>.(Table<T>) -> Table<T> = { it },
    noinline permissions: suspend context(ServerRuntime) AuthAccess<USER>.() -> ModelPermissions<T>,
    noinline prePermissionsForUser: suspend context(ServerRuntime) AuthAccess<USER>.(Table<T>) -> Table<T> = { it },
    noinline denormalizations: DenormalizationBuilder<T>.(Lens<T, T>) -> Unit,
): ModelInfoWithDeno<USER, T, ID> =
    explicitModelInfoWithDeno(
        auth = auth,
        serializer = serializerOrContextual<T>(),
        idSerializer = serializerOrContextual<ID>(),
        tableName = tableName,
        subscope = subscope,
        signals = signals,
        log = log,
        systemAccess = systemAccess,
        postPermissionsForUser = postPermissionsForUser,
        permissions = permissions,
        prePermissionsForUser = prePermissionsForUser,
        denormalizations = denormalizations,
    )

/** Creates a [ModelInfoWithDeno] like [modelInfoWithDeno], using the given [serializer] and [idSerializer]. */
context(builder: ServerBuilder)
public fun <USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> Runtime<Database>.explicitModelInfoWithDeno(
    auth: AuthRequirement<USER>,
    serializer: KSerializer<T>,
    idSerializer: KSerializer<ID>,
    tableName: String,
    subscope: Subscope? = Subscope(tableName.lowercase()),
    signals: context(ServerRuntime) (Table<T>) -> Table<T> = { it },
    log: context(ServerRuntime) AuthAccess<USER>?.(Table<T>) -> Table<T> = { it },
    systemAccess: context(ServerRuntime) (Table<T>) -> Table<T> = { it },
    postPermissionsForUser: suspend context(ServerRuntime) AuthAccess<USER>.(Table<T>) -> Table<T> = { it },
    permissions: suspend context(ServerRuntime) AuthAccess<USER>.() -> ModelPermissions<T>,
    prePermissionsForUser: suspend context(ServerRuntime) AuthAccess<USER>.(Table<T>) -> Table<T> = { it },
    denormalizations: DenormalizationBuilder<T>.(Lens<T, T>) -> Unit,
): ModelInfoWithDeno<USER, T, ID> {
    // Claude: lazy so that models can reference each other's ModelInfo regardless of declaration order.
    val built = lazy { DenormalizationBuilder<T>().apply { denormalizations(IdentityLens(serializer)) }.denormalizations }
    val info = explicitModelInfo(
        auth = auth,
        serializer = serializer,
        idSerializer = idSerializer,
        tableName = tableName,
        subscope = subscope,
        // Claude: the signals slot is inside permissions, so they judge only the caller's modification, and outside the change listeners, so those see the denormalized fields.
        signals = { table -> signals(table).applyDenormalizations(built.value) },
        log = log,
        systemAccess = systemAccess,
        postPermissionsForUser = postPermissionsForUser,
        permissions = permissions,
        prePermissionsForUser = prePermissionsForUser,
    )
    val modelInfo = object : ModelInfoWithDeno<USER, T, ID>, ModelInfo<USER, T, ID> by info {
        override val denormalizations: List<Denormalization<T, *, *, *>> by built

        context(server: ServerRuntime)
        override fun baseTableWithDenormalizations(): Table<T> = baseTable().applyDenormalizations(built.value)
    }

    val listening = AtomicBoolean(false)
    with(builder) {
        path.path("$tableName-deno") bind StartupTask {
            // Claude: listeners live on the ModelInfo, which can outlive the engine, so a second engine in the same process (as in tests) must not add them again.
            if (!listening.compareAndSet(false, true)) return@StartupTask
            for (d in modelInfo.denormalizations) {
                if (!d.watchesReferencedModel) continue
                when (d) {
                    is Denormalization.RequiredRef<T, *, *> -> d.listenForDenormalizations { modelInfo.baseTableWithDenormalizations() }
                    is Denormalization.OptionalRef<T, *, *> -> d.listenForDenormalizations { modelInfo.baseTableWithDenormalizations() }
                }
            }
        }
    }
    return modelInfo
}

context(_: ServerRuntime)
private fun <T : Any> Table<T>.applyDenormalizations(denormalizations: List<Denormalization<T, *, *, *>>): Table<T> =
    // Claude: foldRight so the first declared denormalization intercepts first; later ones read the references it sets.
    denormalizations.foldRight(this) { deno, table ->
        when (deno) {
            is Denormalization.RequiredRef<T, *, *> -> table.denormalizeImpl(deno)
            is Denormalization.OptionalRef<T, *, *> -> table.denormalizeNullableImpl(deno)
        }
    }

private fun <Model : Any, Ref : HasId<RID>, RID : Comparable<RID>> Denormalization.RequiredRef<Model, Ref, RID>.listenForDenormalizations(
    table: context(ServerRuntime) () -> Table<Model>,
) {
    info.registerChangeListener { changes ->
        for ((old, new) in changes.changes) {
            if (old == null || new == null) continue
            val mod = updateDenormalizations(old, new)
            if (!mod.isNothing) table().updateManyIgnoringResult(reference.mapCondition(Condition.Equal(old._id)), mod)
        }
    }
}

private fun <Model : Any, Ref : HasId<RID>, RID : Comparable<RID>> Denormalization.OptionalRef<Model, Ref, RID>.listenForDenormalizations(
    table: context(ServerRuntime) () -> Table<Model>,
) {
    info.registerChangeListener { changes ->
        for ((old, new) in changes.changes) {
            if (old == null) continue
            val mod = updateDenormalizations(old, new)
            val removeRef = if (new == null) reference.mapModification(Modification.Assign(null)) else null
            if (!mod.isNothing || removeRef != null) table().updateManyIgnoringResult(
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
        val id = deno.reference.get(model) ?: return@interceptCreate model
        deno.setDenormalizations(deno.info.table().getOrBadRequest(id)).invoke(model)
    }
    .interceptReplace { model ->
        val id = deno.reference.get(model) ?: return@interceptReplace model
        deno.setDenormalizations(deno.info.table().getOrBadRequest(id)).invoke(model)
    }
    .interceptModification { incomingMod ->
        var newReference: RID? = null
        incomingMod.vetNullable(deno.reference) { if (it is Modification.Assign) newReference = it.value }
        val id = newReference ?: return@interceptModification incomingMod
        Modification.Chain(listOf(incomingMod, deno.setDenormalizations(deno.info.table().getOrBadRequest(id))))
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
        deno.setDenormalizations(reference).invoke(model)
    }
    .interceptReplace { model ->
        val reference = deno.reference.get(model)?.let { deno.info.table().getOrBadRequest(it) }
        deno.setDenormalizations(reference).invoke(model)
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
                listOf(incomingMod, deno.setDenormalizations(source))
            )
        }
        updatedMod
    }

// TODO: Api Recommendations
// Claude:
// - Deleting a row that a required reference points to leaves the referencing rows pointing at nothing with stale values. Pick an explicit policy: block, cascade, or document.
// - Listener updates run inside the request that changed the referenced row. Dispatch them as a Task so a popular row's change doesn't slow or partially fail that request, and so they can retry.
// - Nothing ensures reference fields are indexed; every listener write is updateMany(reference == id). Declare the index, or check for one at startup.
// - insertMany fetches the referenced row once per row per denormalization; fetch each distinct id once.
// - Document that writes skipping ModelInfo.table() (baseTable(), migrations, other services) never propagate, and that a row created while its reference is changing can keep the old value; refresh is the repair for both.
// - Names: spell out "Deno" (ModelInfoWithDeno, modelInfoWithDeno, the "$tableName-deno" startup path), e.g. DenormalizedModelInfo.
// - Once polished, consider merging into modelInfo as a denormalizations parameter with denormalizations on ModelInfo, removing ModelInfoWithDeno.
