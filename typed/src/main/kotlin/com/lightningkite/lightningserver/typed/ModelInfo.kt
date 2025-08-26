package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.CollectionChanges
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.ModelPermissions
import com.lightningkite.services.database.default
import com.lightningkite.services.database.serializerOrContextual
import com.lightningkite.services.database.withChangeListeners
import com.lightningkite.services.database.withPermissions
import kotlinx.serialization.KSerializer

public interface ModelInfo<SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> {
    public val serializer: KSerializer<T>
    public val idSerializer: KSerializer<ID>
    public val auth: AuthRequirement<SUBJECT>

    public val collectionName: String
        get() = serializer.descriptor.serialName.substringBefore('<').substringAfterLast('.')

    public fun registerChangeListener(action: suspend (CollectionChanges<T>) -> Unit)

    context(server: ServerRuntime) public fun baseCollection(): Table<T>
    context(server: ServerRuntime) public fun collection(): Table<T>

    context(server: ServerRuntime) public suspend fun collection(auth: AuthAccess<SUBJECT>): Table<T>
    context(server: ServerRuntime) public suspend fun permissions(auth: AuthAccess<SUBJECT>): ModelPermissions<T>

    context(server: ServerRuntime) public suspend fun defaultItem(auth: Authentication<SUBJECT & Any>?): T = serializer.default()
    context(server: ServerRuntime) public fun exampleItem(): T? = null
}

public inline fun <reified USER : HasId<*>?, reified T : HasId<ID>, reified ID : Comparable<ID>> Runtime<Database>.modelInfo(
    auth: AuthRequirement<USER>,
    serializer: KSerializer<T> = serializerOrContextual<T>(),
    idSerializer: KSerializer<ID> = serializerOrContextual<ID>(),
    collectionName: String = serializer.descriptor.serialName.substringBefore('<').substringAfterLast('.'),
    crossinline signals: context(ServerRuntime) (Table<T>) -> Table<T> = { it },
    crossinline log: context(ServerRuntime) AuthAccess<USER>?.(Table<T>) -> Table<T> = { it },
    crossinline systemAccess: context(ServerRuntime) (Table<T>) -> Table<T> = { it },
    noinline postPermissionsForUser: suspend context(ServerRuntime) AuthAccess<USER>.(Table<T>) -> Table<T> = { it },
    crossinline permissions: suspend context(ServerRuntime) AuthAccess<USER>.() -> ModelPermissions<T>,
    noinline prePermissionsForUser: suspend context(ServerRuntime) AuthAccess<USER>.(Table<T>) -> Table<T> = { it },
): ModelInfo<USER, T, ID> = object : ModelInfo<USER, T, ID> {
    override val serializer: KSerializer<T> = serializer
    override val idSerializer: KSerializer<ID> = idSerializer

    override val auth: AuthRequirement<USER> = auth

    context(server: ServerRuntime)
    override fun baseCollection(): Table<T> = this@modelInfo().collection(serializer, collectionName)

    val changeListeners = ArrayList<suspend (CollectionChanges<T>) -> Unit>()
    override fun registerChangeListener(action: suspend (CollectionChanges<T>) -> Unit) {
        changeListeners += action
    }

    context(server: ServerRuntime)
    override suspend fun permissions(auth: AuthAccess<USER>): ModelPermissions<T> = permissions(auth)

    context(server: ServerRuntime)
    fun collectionWithSignals() = signals(baseCollection().withChangeListeners(changeListeners))

    context(server: ServerRuntime)
    override suspend fun collection(auth: AuthAccess<USER>): Table<T> = collectionWithSignals()
        .let { log(auth, it) }
        .let { postPermissionsForUser(auth, it) }
        .withPermissions(permissions(auth))
        .let { prePermissionsForUser(auth, it) }

    context(server: ServerRuntime)
    override fun collection(): Table<T> = systemAccess(log(null, collectionWithSignals()))
}


public fun <USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> Runtime<Database>.modelInfo2(
    auth: AuthRequirement<USER>,
    serializer: KSerializer<T>,
    idSerializer: KSerializer<ID>,
    collectionName: String = serializer.descriptor.serialName.substringBefore('<').substringAfterLast('.'),
    signals: context(ServerRuntime) (Table<T>) -> Table<T> = { it },
    log: context(ServerRuntime) AuthAccess<USER>?.(Table<T>) -> Table<T> = { it },
    systemAccess: context(ServerRuntime) (Table<T>) -> Table<T> = { it },
    postPermissionsForUser: suspend context(ServerRuntime) AuthAccess<USER>.(Table<T>) -> Table<T> = { it },
    permissions: suspend context(ServerRuntime) AuthAccess<USER>.() -> ModelPermissions<T>,
    prePermissionsForUser: suspend context(ServerRuntime) AuthAccess<USER>.(Table<T>) -> Table<T> = { it },
): ModelInfo<USER, T, ID> = object : ModelInfo<USER, T, ID> {
    override val serializer: KSerializer<T> = serializer
    override val idSerializer: KSerializer<ID> = idSerializer

    override val auth: AuthRequirement<USER> = auth

    context(server: ServerRuntime)
    override fun baseCollection(): Table<T> = this@modelInfo2().collection(serializer, collectionName)

    val changeListeners = ArrayList<suspend (CollectionChanges<T>) -> Unit>()
    override fun registerChangeListener(action: suspend (CollectionChanges<T>) -> Unit) {
        changeListeners += action
    }

    context(server: ServerRuntime)
    override suspend fun permissions(auth: AuthAccess<USER>): ModelPermissions<T> = permissions(auth)

    context(server: ServerRuntime)
    fun collectionWithSignals() = signals(baseCollection().withChangeListeners(changeListeners))

    context(server: ServerRuntime)
    override suspend fun collection(auth: AuthAccess<USER>): Table<T> = collectionWithSignals()
        .let { log(auth, it) }
        .let { postPermissionsForUser(auth, it) }
        .withPermissions(permissions(auth))
        .let { prePermissionsForUser(auth, it) }

    context(server: ServerRuntime)
    override fun collection(): Table<T> = systemAccess(log(null, collectionWithSignals()))
}