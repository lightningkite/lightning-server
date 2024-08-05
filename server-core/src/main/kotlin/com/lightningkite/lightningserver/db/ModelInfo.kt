package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.typed.AuthAccessor
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

@Suppress("DEPRECATION")
@Deprecated("User newer version with auth accessor instead, as it enables more potential optimizations.")
inline fun <reified USER : HasId<*>, reified T : HasId<ID>, reified ID : Comparable<ID>> ModelInfo(
    noinline getCollection: () -> FieldCollection<T>,
    noinline getBaseCollection: () -> FieldCollection<T> = { getCollection() },
    noinline forUser: suspend FieldCollection<T>.(principal: USER) -> FieldCollection<T>,
    modelName: String = Serialization.module.serializer<T>().descriptor.serialName.substringBefore('<')
        .substringAfterLast('.'),
) = ModelInfo(
    serialization = ModelSerializationInfo<T, ID>(),
    authOptions = com.lightningkite.lightningserver.auth.authOptions<USER>(),
    getCollection = getCollection,
    getBaseCollection = getBaseCollection,
    forUser = forUser,
    modelName = modelName,
)

@Deprecated("User newer version with auth accessor instead, as it enables more potential optimizations.")
fun <USER : HasId<*>, T : HasId<ID>, ID : Comparable<ID>> ModelInfo(
    serialization: ModelSerializationInfo<T, ID>,
    authOptions: AuthOptions<USER>,
    getCollection: () -> FieldCollection<T>,
    getBaseCollection: () -> FieldCollection<T> = { getCollection() },
    forUser: suspend FieldCollection<T>.(principal: USER) -> FieldCollection<T>,
    modelName: String = serialization.serializer.descriptor.serialName.substringBefore('<').substringAfterLast('.')
) = object : ModelInfo<USER, T, ID> {
    override val authOptions: AuthOptions<USER> = authOptions
    override val serialization: ModelSerializationInfo<T, ID> = serialization
    override fun baseCollection(): FieldCollection<T> = getBaseCollection()
    override fun registerChangeListener(action: suspend (CollectionChanges<T>) -> Unit) {
        changeListeners.add(action)
    }
    val changeListeners = ArrayList<suspend (CollectionChanges<T>)->Unit>()
    override fun collection(): FieldCollection<T> = getCollection().withChangeListeners(changeListeners)
    override suspend fun collection(auth: AuthAccessor<USER>): FieldCollection<T> = forUser(collection(), auth.user())
    override val collectionName: String = modelName
}

fun <USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> modelInfo(
    serialization: ModelSerializationInfo<T, ID>,
    authOptions: AuthOptions<USER>,
    getBaseCollection: () -> FieldCollection<T>,
    getCollection: (collection: FieldCollection<T>) -> FieldCollection<T> = { it },
    forUser: suspend AuthAccessor<USER>.(collection: FieldCollection<T>) -> FieldCollection<T> = { it },
    modelName: String = serialization.serializer.descriptor.serialName.substringBefore('<').substringAfterLast('.')
) = object : ModelInfo<USER, T, ID> {
    override val authOptions: AuthOptions<USER> = authOptions
    override val serialization: ModelSerializationInfo<T, ID> = serialization
    override fun baseCollection(): FieldCollection<T> = getBaseCollection()
    override fun registerChangeListener(action: suspend (CollectionChanges<T>) -> Unit) {
        changeListeners.add(action)
    }
    val changeListeners = ArrayList<suspend (CollectionChanges<T>)->Unit>()
    override fun collection(): FieldCollection<T> = getCollection(this.baseCollection().withChangeListeners(changeListeners))
    override suspend fun collection(auth: AuthAccessor<USER>): FieldCollection<T> =
        auth.forUser(this.collection())

    override val collectionName: String = modelName
}

/*
T.serializer().modelInfo(database(), "collectionName") {
    forAuth<User> {
        it.withPermissions(ModelPermission(

        ))
    }
    forAuth<Admin> { it }
}
 */

interface ModelInfo<USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> {
    val serialization: ModelSerializationInfo<T, ID>
    val authOptions: AuthOptions<USER>
    val collectionName: String
        get() = serialization.serializer.descriptor.serialName.substringBefore('<').substringAfterLast('.')

    fun baseCollection(): FieldCollection<T> = collection()
    fun collection(): FieldCollection<T> = baseCollection()
    fun registerChangeListener(action: suspend (CollectionChanges<T>)->Unit): Unit
    suspend fun collection(auth: AuthAccessor<USER>): FieldCollection<T>
    suspend fun collection(user: USER): FieldCollection<T> = collection(AuthAccessor.test(user))
}


