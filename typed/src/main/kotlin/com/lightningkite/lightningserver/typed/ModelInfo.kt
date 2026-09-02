package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.PreDeployTask
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.*
import kotlinx.serialization.KSerializer

public interface ModelInfo<SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> {
    public val serializer: KSerializer<T>
    public val idSerializer: KSerializer<ID>

    public val auth: AuthRequirement<SUBJECT>

    public val registration: DatabaseTableRegistration<T>

    public object Scopes {
        public val create: Subscope = Subscope("create")
        public val read: Subscope = Subscope("read")
        public val update: Subscope = Subscope("update")
        public val delete: Subscope = Subscope("delete")
    }

    public val tableName: String
        get() = serializer.descriptor.serialName.substringBefore('/').substringBefore('<').substringAfterLast('.')

    public fun registerChangeListener(action: suspend context(ServerRuntime) (CollectionChanges<T>) -> Unit)

    /**
     * The table without permissions, but **with** auditing.
     *
     * This is what "I need the raw table" almost always means: skip the caller's read/write masks,
     * not skip the record of what was touched. Goes through the same `log` decorator as [table], so
     * an audited model read this way is still recorded.
     */
    context(server: ServerRuntime)
    public fun baseTable(): Table<T>

    /**
     * The table with nothing applied at all — no permissions, no signals, and **no auditing**.
     *
     * Requires opting in to [UnauditedDatabaseAccess], so that every bypass in a codebase can be
     * found by grepping for it. See that annotation for when this is legitimate.
     */
    @UnauditedDatabaseAccess
    context(server: ServerRuntime)
    public fun dangerouslyDirectTable(): Table<T>

    context(server: ServerRuntime)
    public fun table(): Table<T>

    context(server: ServerRuntime)
    public suspend fun table(auth: AuthAccess<SUBJECT>): Table<T>
    context(server: ServerRuntime)
    public suspend fun permissions(auth: AuthAccess<SUBJECT>): ModelPermissions<T>

    context(server: ServerRuntime)
    public suspend fun defaultItem(auth: Authentication<SUBJECT & Any>?): T = serializer.default()
    context(server: ServerRuntime)
    public fun exampleItem(): T? = null
}

context(builder: ServerBuilder)
public inline fun <reified USER : HasId<*>?, reified T : HasId<ID>, reified ID : Comparable<ID>> Runtime<Database>.modelInfo(
    auth: AuthRequirement<USER>,
    tableName: String,
    subscope: Subscope? = Subscope(tableName.lowercase()),
    crossinline signals: context(ServerRuntime) (Table<T>) -> Table<T> = { it },
    crossinline log: context(ServerRuntime) AuthAccess<USER>?.(Table<T>) -> Table<T> = { it },
    crossinline systemAccess: context(ServerRuntime) (Table<T>) -> Table<T> = { it },
    noinline postPermissionsForUser: suspend context(ServerRuntime) AuthAccess<USER>.(Table<T>) -> Table<T> = { it },
    crossinline permissions: suspend context(ServerRuntime) AuthAccess<USER>.() -> ModelPermissions<T>,
    noinline prePermissionsForUser: suspend context(ServerRuntime) AuthAccess<USER>.(Table<T>) -> Table<T> = { it },
): ModelInfo<USER, T, ID> = object : ModelInfo<USER, T, ID> {
    override val serializer: KSerializer<T> = serializerOrContextual<T>()
    override val idSerializer: KSerializer<ID> = serializerOrContextual<ID>()

    override val auth: AuthRequirement<USER> = subscope?.let { auth.subscope(it) } ?: auth

    // registerTable defines the table, registers it, and creates its (once-per-deploy) prepare task.
    override val registration: DatabaseTableRegistration<T> = this@modelInfo.registerTable(tableName, serializer)

    @UnauditedDatabaseAccess
    context(server: ServerRuntime)
    override fun dangerouslyDirectTable(): Table<T> = registration()

    context(server: ServerRuntime)
    override fun baseTable(): Table<T> = log(null, registration())

    override val tableName: String get() = tableName

    val changeListeners = ArrayList<suspend context(ServerRuntime) (CollectionChanges<T>) -> Unit>()
    override fun registerChangeListener(action: suspend context(ServerRuntime) (CollectionChanges<T>) -> Unit) {
        changeListeners += action
    }

    context(server: ServerRuntime)
    override suspend fun permissions(auth: AuthAccess<USER>): ModelPermissions<T> = permissions(auth)

    // Built on the undecorated table on purpose: table() and table(auth) apply `log` themselves, and
    // going through baseTable() here would decorate twice and record every query in duplicate.
    @OptIn(UnauditedDatabaseAccess::class)
    context(server: ServerRuntime)
    fun collectionWithSignals() =
        signals(dangerouslyDirectTable().withServerRuntimeChangeListeners(changeListeners))

    context(server: ServerRuntime)
    override suspend fun table(auth: AuthAccess<USER>): Table<T> = collectionWithSignals()
        .let { log(auth, it) }
        .let { postPermissionsForUser(auth, it) }
        .withPermissions(permissions(auth))
        .let { prePermissionsForUser(auth, it) }

    context(server: ServerRuntime)
    override fun table(): Table<T> = systemAccess(log(null, collectionWithSignals()))
}


context(builder: ServerBuilder)
public fun <USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> Runtime<Database>.explicitModelInfo(
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
): ModelInfo<USER, T, ID> = object : ModelInfo<USER, T, ID> {
    override val serializer: KSerializer<T> = serializer
    override val idSerializer: KSerializer<ID> = idSerializer

    override val auth: AuthRequirement<USER> = subscope?.let { auth.subscope(it) } ?: auth

    // registerTable defines the table, registers it, and creates its (once-per-deploy) prepare task.
    override val registration: DatabaseTableRegistration<T> =
        with(builder) { this@explicitModelInfo.registerTable(tableName, serializer) }

    @UnauditedDatabaseAccess
    context(server: ServerRuntime)
    override fun dangerouslyDirectTable(): Table<T> = registration()

    context(server: ServerRuntime)
    override fun baseTable(): Table<T> = log(null, registration())

    override val tableName: String
        get() = tableName

    val changeListeners = ArrayList<suspend context(ServerRuntime) (CollectionChanges<T>) -> Unit>()
    override fun registerChangeListener(action: suspend context(ServerRuntime) (CollectionChanges<T>) -> Unit) {
        changeListeners += action
    }

    context(server: ServerRuntime)
    override suspend fun permissions(auth: AuthAccess<USER>): ModelPermissions<T> = permissions(auth)

    // Undecorated on purpose: table() and table(auth) apply `log` themselves, so routing this through
    // baseTable() would decorate twice and record every query in duplicate.
    @OptIn(UnauditedDatabaseAccess::class)
    context(server: ServerRuntime)
    fun collectionWithSignals() =
        signals(dangerouslyDirectTable().withServerRuntimeChangeListeners(changeListeners))

    context(server: ServerRuntime)
    override suspend fun table(auth: AuthAccess<USER>): Table<T> = collectionWithSignals()
        .let { log(auth, it) }
        .let { postPermissionsForUser(auth, it) }
        .withPermissions(permissions(auth))
        .let { prePermissionsForUser(auth, it) }

    context(server: ServerRuntime)
    override fun table(): Table<T> = systemAccess(log(null, collectionWithSignals()))
}

context(_: ServerRuntime)
public suspend fun <USER : HasId<*>, T : HasId<ID>, ID : Comparable<ID>> ModelInfo<USER, T, ID>.table(auth: Authentication<USER>): Table<T> =
    table(AuthAccess(auth))

@JvmName("authTableNullable")
context(_: ServerRuntime)
public suspend fun <USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> ModelInfo<USER, T, ID>.table(auth: Authentication<USER & Any>?): Table<T> =
    table(AuthAccess(auth))

/**
 * Marks a way of reaching a table that skips the [ModelInfo] `log` decorator, and therefore skips
 * auditing.
 *
 * Opting in is deliberately an error to ignore rather than a warning. The point is not to forbid the
 * bypass — there are legitimate uses, such as a migration that must not multiply the audit log by the
 * size of the table — but to make every one of them **greppable**. `grep -rn UnauditedDatabaseAccess`
 * should return the complete list of places in a codebase where an audited model is touched without a
 * record, which is exactly the question an auditor asks and the one that was previously unanswerable.
 *
 * If you are reaching for this because auditing is inconvenient, use [ModelInfo.baseTable] instead: it
 * skips permissions, which is usually what "I need the raw table" actually means, while still being
 * recorded.
 */
@RequiresOptIn(
    "This reaches the table without the audit decorator, so nothing records what it reads or writes. " +
        "Prefer baseTable(), which skips permissions but is still audited. If you genuinely need to " +
        "bypass the audit log, opt in explicitly so the bypass is greppable.",
    RequiresOptIn.Level.ERROR,
)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class UnauditedDatabaseAccess
