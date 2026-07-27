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

    public val tableDefinition: DatabaseTableDefinition<T>
    public val preDeployTask: PreDeployTask

    public object Scopes {
        public val create: Subscope = Subscope("create")
        public val read: Subscope = Subscope("read")
        public val update: Subscope = Subscope("update")
        public val delete: Subscope = Subscope("delete")
    }

    public val tableName: String
        get() = serializer.descriptor.serialName.substringBefore('/').substringBefore('<').substringAfterLast('.')

    public fun registerChangeListener(action: suspend context(ServerRuntime) (CollectionChanges<T>) -> Unit)

    context(server: ServerRuntime)
    public fun baseTable(): Table<T>
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
    tableName: String = serializerOrContextual<T>().descriptor.serialName.substringBefore('/').substringBefore('<')
        .substringAfterLast('.'),
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

    override val tableDefinition: DatabaseTableDefinition<T> = DatabaseTableDefinition(serializer, tableName)
    // Table/index reconciliation runs as a pre-deploy task: once per deploy, before the new version
    // serves, rather than on every instance's boot (keeps it off the cold-start path). It is
    // convergent (reconciles the DB to the current model), so running it every deploy is correct.
    override val preDeployTask: PreDeployTask = with(builder) {
        path.path(tableName) bind PreDeployTask {
            this@modelInfo().prepare(tableDefinition)
        }
    }

    context(server: ServerRuntime)
    override fun baseTable(): Table<T> = this@modelInfo().table(tableDefinition)

    override val tableName: String get() = tableName

    val changeListeners = ArrayList<suspend context(ServerRuntime) (CollectionChanges<T>) -> Unit>()
    override fun registerChangeListener(action: suspend context(ServerRuntime) (CollectionChanges<T>) -> Unit) {
        changeListeners += action
    }

    context(server: ServerRuntime)
    override suspend fun permissions(auth: AuthAccess<USER>): ModelPermissions<T> = permissions(auth)

    context(server: ServerRuntime)
    fun collectionWithSignals() = signals(baseTable().withServerRuntimeChangeListeners(changeListeners))

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
    tableName: String = serializer.descriptor.serialName.substringBefore('<').substringAfterLast('.'),
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

    override val tableDefinition: DatabaseTableDefinition<T> = DatabaseTableDefinition(serializer, tableName)
    // Table/index reconciliation runs as a pre-deploy task: once per deploy, before the new version
    // serves, rather than on every instance's boot (keeps it off the cold-start path). It is
    // convergent (reconciles the DB to the current model), so running it every deploy is correct.
    override val preDeployTask: PreDeployTask = with(builder) {
        path.path(tableName) bind PreDeployTask {
            this@explicitModelInfo().prepare(tableDefinition)
        }
    }

    context(server: ServerRuntime)
    override fun baseTable(): Table<T> = this@explicitModelInfo().table(tableDefinition)

    override val tableName: String
        get() = tableName

    val changeListeners = ArrayList<suspend context(ServerRuntime) (CollectionChanges<T>) -> Unit>()
    override fun registerChangeListener(action: suspend context(ServerRuntime) (CollectionChanges<T>) -> Unit) {
        changeListeners += action
    }

    context(server: ServerRuntime)
    override suspend fun permissions(auth: AuthAccess<USER>): ModelPermissions<T> = permissions(auth)

    context(server: ServerRuntime)
    fun collectionWithSignals() = signals(baseTable().withServerRuntimeChangeListeners(changeListeners))

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