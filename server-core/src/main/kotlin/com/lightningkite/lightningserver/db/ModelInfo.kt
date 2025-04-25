package com.lightningkite.lightningserver.db

import com.lightningkite.lightningserver.db.*
import com.lightningkite.lightningserver.auth.AuthOption
import com.lightningkite.serialization.*
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.RequestAuth
import com.lightningkite.lightningserver.auth.authOptions
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.typed.AuthAccessor
import com.lightningkite.serialization.contextualSerializerIfHandled
import kotlinx.serialization.serializer

interface ModelInfo<out USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> {
    val serialization: ModelSerializationInfo<T, ID>
    val authOptions: AuthOptions<USER>
    val collectionName: String
        get() = serialization.serializer.descriptor.serialName.substringBefore('<').substringAfterLast('.')

    fun baseCollection(): FieldCollection<T>
    fun registerChangeListener(action: suspend (CollectionChanges<T>) -> Unit): Unit
    suspend fun permissions(auth: AuthAccessor<@UnsafeVariance USER>): ModelPermissions<T>
    suspend fun collection(auth: AuthAccessor<@UnsafeVariance USER>): FieldCollection<T>
    fun collection(): FieldCollection<T>

    suspend fun defaultItem(auth: RequestAuth<USER & Any>?): T = serialization.serializer.default()
    fun exampleItem(): T? = null
}

suspend fun <USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> ModelInfo<USER, T, ID>.collection(user: USER): FieldCollection<T> =
    collection(AuthAccessor.test(user))


inline fun <reified USER : HasId<*>?, reified T : HasId<ID>, reified ID : Comparable<ID>> (() -> Database).modelInfo(
    authOptions: AuthOptions<USER> = authOptions<USER>(),
    serialization: ModelSerializationInfo<T, ID> = ModelSerializationInfo(),
    collectionName: String = serialization.serializer.descriptor.serialName.substringBefore('<')
        .substringAfterLast('.'),
    crossinline signals: (FieldCollection<T>) -> FieldCollection<T> = { it },
    crossinline log: AuthAccessor<USER>?.(FieldCollection<T>) -> FieldCollection<T> = { it },
    crossinline systemAccess: (FieldCollection<T>) -> FieldCollection<T> = { it },
    noinline postPermissionsForUser: suspend AuthAccessor<USER>.(FieldCollection<T>) -> FieldCollection<T> = { it },
    crossinline permissions: suspend AuthAccessor<USER>.() -> ModelPermissions<T>,
    noinline prePermissionsForUser: suspend AuthAccessor<USER>.(FieldCollection<T>) -> FieldCollection<T> = { it },
) = object : ModelInfo<USER, T, ID> {
    override val serialization: ModelSerializationInfo<T, ID> = serialization
    override val authOptions: AuthOptions<USER> = authOptions
    override fun baseCollection(): FieldCollection<T> = this@modelInfo().collection(
        serialization.serializer,
        collectionName
    )
    val changeListeners = ArrayList<suspend (CollectionChanges<T>) -> Unit>()
    override fun registerChangeListener(action: suspend (CollectionChanges<T>) -> Unit) {
        changeListeners += action
    }
    override suspend fun permissions(auth: AuthAccessor<USER>): ModelPermissions<T> = permissions(auth)
    fun collectionWithSignals() = baseCollection().withChangeListeners(changeListeners).let { signals(it) }
    override suspend fun collection(auth: AuthAccessor<USER>): FieldCollection<T> = collectionWithSignals()
        .let { log(auth, it) }
        .let { postPermissionsForUser(auth, it) }
        .let { it.withPermissions(permissions(auth)) }
        .let { prePermissionsForUser(auth, it) }
    override fun collection(): FieldCollection<T> = systemAccess(log(null, collectionWithSignals()))
}