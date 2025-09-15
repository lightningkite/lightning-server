package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.RequiredScope
import com.lightningkite.lightningserver.auth.Subscope
import com.lightningkite.lightningserver.auth.subscope
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.CollectionChanges
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.EntryChange
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.ModelPermissions
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.SortPart
import com.lightningkite.services.database.default
import com.lightningkite.services.database.serializerOrContextual
import com.lightningkite.services.database.withChangeListeners
import com.lightningkite.services.database.withPermissions
import kotlinx.serialization.KSerializer

public interface ModelInfo<SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> {
    public val serializer: KSerializer<T>
    public val idSerializer: KSerializer<ID>

    public val auth: AuthRequirement<SUBJECT>

    public companion object {
        public val createSubscope: Subscope = Subscope("create")
        public val readSubscope: Subscope = Subscope("read")
        public val updateSubscope: Subscope = Subscope("update")
        public val deleteSubscope: Subscope = Subscope("delete")
    }

    public val collectionName: String
        get() = serializer.descriptor.serialName.substringBefore('/').substringBefore('<').substringAfterLast('.')

    public fun registerChangeListener(action: suspend context(ServerRuntime) (CollectionChanges<T>) -> Unit)

    context(server: ServerRuntime) public fun baseCollection(): Table<T>
    context(server: ServerRuntime) public fun collection(): Table<T>

    context(server: ServerRuntime) public suspend fun collection(auth: AuthAccess<SUBJECT>): Table<T>
    context(server: ServerRuntime) public suspend fun permissions(auth: AuthAccess<SUBJECT>): ModelPermissions<T>

    context(server: ServerRuntime) public suspend fun defaultItem(auth: Authentication<SUBJECT & Any>?): T = serializer.default()
    context(server: ServerRuntime) public fun exampleItem(): T? = null
}

public inline fun <reified USER : HasId<*>?, reified T : HasId<ID>, reified ID : Comparable<ID>> Runtime<Database>.modelInfo(
    auth: AuthRequirement<USER>,
    collectionName: String = serializerOrContextual<T>().descriptor.serialName.substringBefore('/').substringBefore('<').substringAfterLast('.'),
    scopeName: Subscope = Subscope(collectionName.lowercase()),
    crossinline signals: context(ServerRuntime) (Table<T>) -> Table<T> = { it },
    crossinline log: context(ServerRuntime) AuthAccess<USER>?.(Table<T>) -> Table<T> = { it },
    crossinline systemAccess: context(ServerRuntime) (Table<T>) -> Table<T> = { it },
    noinline postPermissionsForUser: suspend context(ServerRuntime) AuthAccess<USER>.(Table<T>) -> Table<T> = { it },
    crossinline permissions: suspend context(ServerRuntime) AuthAccess<USER>.() -> ModelPermissions<T>,
    noinline prePermissionsForUser: suspend context(ServerRuntime) AuthAccess<USER>.(Table<T>) -> Table<T> = { it },
): ModelInfo<USER, T, ID> = object : ModelInfo<USER, T, ID> {
    override val serializer: KSerializer<T> = serializerOrContextual<T>()
    override val idSerializer: KSerializer<ID> = serializerOrContextual<ID>()

    override val auth: AuthRequirement<USER> = auth.subscope(scopeName)

    context(server: ServerRuntime)
    override fun baseCollection(): Table<T> = this@modelInfo().table(serializer, collectionName)

    override val collectionName: String
        get() = collectionName

    val changeListeners = ArrayList<suspend context(ServerRuntime) (CollectionChanges<T>) -> Unit>()
    override fun registerChangeListener(action: suspend context(ServerRuntime) (CollectionChanges<T>) -> Unit) {
        changeListeners += action
    }

    context(server: ServerRuntime)
    override suspend fun permissions(auth: AuthAccess<USER>): ModelPermissions<T> = permissions(auth)

    context(server: ServerRuntime)
    fun collectionWithSignals() = signals(baseCollection().withServerRuntimeChangeListeners(changeListeners))

    context(server: ServerRuntime)
    override suspend fun collection(auth: AuthAccess<USER>): Table<T> = collectionWithSignals()
        .let { log(auth, it) }
        .let { postPermissionsForUser(auth, it) }
        .withPermissions(permissions(auth))
        .let { prePermissionsForUser(auth, it) }

    context(server: ServerRuntime)
    override fun collection(): Table<T> = systemAccess(log(null, collectionWithSignals()))
}


public fun <USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> Runtime<Database>.modelInfo(
    auth: AuthRequirement<USER>,
    serializer: KSerializer<T>,
    idSerializer: KSerializer<ID>,
    collectionName: String = serializer.descriptor.serialName.substringBefore('<').substringAfterLast('.'),
    scopeName: Subscope = Subscope(collectionName.lowercase()),
    signals: context(ServerRuntime) (Table<T>) -> Table<T> = { it },
    log: context(ServerRuntime) AuthAccess<USER>?.(Table<T>) -> Table<T> = { it },
    systemAccess: context(ServerRuntime) (Table<T>) -> Table<T> = { it },
    postPermissionsForUser: suspend context(ServerRuntime) AuthAccess<USER>.(Table<T>) -> Table<T> = { it },
    permissions: suspend context(ServerRuntime) AuthAccess<USER>.() -> ModelPermissions<T>,
    prePermissionsForUser: suspend context(ServerRuntime) AuthAccess<USER>.(Table<T>) -> Table<T> = { it },
): ModelInfo<USER, T, ID> = object : ModelInfo<USER, T, ID> {
    override val serializer: KSerializer<T> = serializer
    override val idSerializer: KSerializer<ID> = idSerializer

    override val auth: AuthRequirement<USER> = auth.subscope(scopeName)

    context(server: ServerRuntime)
    override fun baseCollection(): Table<T> = this@modelInfo().table(serializer, collectionName)

    override val collectionName: String
        get() = collectionName

    val changeListeners = ArrayList<suspend context(ServerRuntime) (CollectionChanges<T>) -> Unit>()
    override fun registerChangeListener(action: suspend context(ServerRuntime) (CollectionChanges<T>) -> Unit) {
        changeListeners += action
    }

    context(server: ServerRuntime)
    override suspend fun permissions(auth: AuthAccess<USER>): ModelPermissions<T> = permissions(auth)

    context(server: ServerRuntime)
    fun collectionWithSignals() = signals(baseCollection().withServerRuntimeChangeListeners(changeListeners))

    context(server: ServerRuntime)
    override suspend fun collection(auth: AuthAccess<USER>): Table<T> = collectionWithSignals()
        .let { log(auth, it) }
        .let { postPermissionsForUser(auth, it) }
        .withPermissions(permissions(auth))
        .let { prePermissionsForUser(auth, it) }

    context(server: ServerRuntime)
    override fun collection(): Table<T> = systemAccess(log(null, collectionWithSignals()))
}



public context(server: ServerRuntime)
fun <Model : HasId<ID>, ID : Comparable<ID>> Table<Model>.withServerRuntimeChangeListeners(
    changeListeners: List<suspend context(ServerRuntime) (CollectionChanges<Model>) -> Unit>
): Table<Model> = object : Table<Model> by this@withServerRuntimeChangeListeners {
    override val wraps = this@withServerRuntimeChangeListeners

    suspend fun changed(changes: List<EntryChange<Model>>) {
        val changeSet = CollectionChanges(changes)
        changeListeners.forEach { it.invoke(server, changeSet) }
    }

    override suspend fun insert(models: Iterable<Model>): List<Model> = wraps.insert(models)
        .also { changed(it.map { EntryChange(null, it) }) }

    override suspend fun deleteMany(condition: Condition<Model>): List<Model> = wraps.deleteMany(condition)
        .also { changed(it.map { EntryChange(it, null) }) }

    override suspend fun deleteOne(condition: Condition<Model>, orderBy: List<SortPart<Model>>): Model? =
        wraps.deleteOne(condition, orderBy)
            .also { changed(listOf(EntryChange(it, null))) }

    override suspend fun replaceOne(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>
    ): EntryChange<Model> = wraps.replaceOne(condition, model, orderBy)
        .also { changed(listOf(it)) }

    override suspend fun updateOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>
    ): EntryChange<Model> = wraps.updateOne(condition, modification, orderBy)
        .also { changed(listOf(it)) }

    override suspend fun upsertOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): EntryChange<Model> = wraps.upsertOne(condition, modification, model)
        .also { changed(listOf(it)) }

    override suspend fun updateMany(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): CollectionChanges<Model> = wraps.updateMany(condition, modification)
        .also { changed(it.changes) }


    override suspend fun replaceOneIgnoringResult(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>
    ): Boolean =
        if (changeListeners.isEmpty()) wraps.replaceOneIgnoringResult(condition, model, orderBy) else replaceOne(
            condition,
            model,
            orderBy
        ).new != null

    override suspend fun upsertOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): Boolean =
        if (changeListeners.isEmpty()) wraps.upsertOneIgnoringResult(condition, modification, model) else upsertOne(
            condition,
            modification,
            model
        ).old != null

    override suspend fun updateOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>
    ): Boolean =
        if (changeListeners.isEmpty()) wraps.updateOneIgnoringResult(condition, modification, orderBy) else updateOne(
            condition,
            modification,
            orderBy
        ).new != null

    override suspend fun updateManyIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): Int = if (changeListeners.isEmpty()) wraps.updateManyIgnoringResult(
        condition,
        modification
    ) else updateMany(condition, modification).changes.size

    override suspend fun deleteOneIgnoringOld(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>
    ): Boolean = if (changeListeners.isEmpty()) wraps.deleteOneIgnoringOld(condition, orderBy) else deleteOne(
        condition,
        orderBy
    ) != null

    override suspend fun deleteManyIgnoringOld(condition: Condition<Model>): Int =
        if (changeListeners.isEmpty()) wraps.deleteManyIgnoringOld(condition) else deleteMany(condition).size

}