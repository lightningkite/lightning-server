package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.RequestAuth
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.typed.AuthAccessor

@Suppress("DEPRECATION")
@Deprecated("User newer version with auth accessor instead, as it enables more potential optimizations.")
inline fun <reified USER: HasId<*>?, reified T : HasId<ID>, reified ID : Comparable<ID>> ModelInfoWithDefault(
    noinline getCollection: () -> FieldCollection<T>,
    noinline getBaseCollection: () -> FieldCollection<T> = { getCollection() },
    noinline forUser: suspend FieldCollection<T>.(principal: USER) -> FieldCollection<T>,
    modelName: String = Serialization.module.contextualSerializerIfHandled<T>().descriptor.serialName.substringBefore('<').substringAfterLast('.'),
    noinline defaultItem: suspend (auth: USER) -> T,
    noinline exampleItem: ()->T? = { null },
): ModelInfoWithDefault<USER, T, ID> = ModelInfoWithDefault(
    serialization = ModelSerializationInfo<T, ID>(),
    authOptions = com.lightningkite.lightningserver.auth.authOptions<USER>(),
    getCollection = getCollection,
    getBaseCollection = getBaseCollection,
    forUser = forUser,
    modelName = modelName,
    defaultItem = defaultItem,
    exampleItem = exampleItem,
)

@Deprecated("User newer version with auth accessor instead, as it enables more potential optimizations.")
fun <USER: HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> ModelInfoWithDefault(
    serialization: ModelSerializationInfo<T, ID>,
    authOptions: AuthOptions<USER>,
    getCollection: () -> FieldCollection<T>,
    getBaseCollection: () -> FieldCollection<T> = { getCollection() },
    forUser: suspend FieldCollection<T>.(principal: USER) -> FieldCollection<T>,
    modelName: String = serialization.serializer.descriptor.serialName.substringBefore('<').substringAfterLast('.'),
    defaultItem: suspend (auth: USER) -> T,
    exampleItem: ()->T? = { null },
) = object : ModelInfoWithDefault<USER, T, ID> {
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
    @Suppress("UNCHECKED_CAST")
    override suspend fun defaultItem(auth: RequestAuth<USER & Any>?): T = defaultItem(auth?.get() as USER)
    override fun exampleItem(): T? = exampleItem()
}

fun <USER: HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> modelInfoWithDefault(
    serialization: ModelSerializationInfo<T, ID>,
    authOptions: AuthOptions<USER>,
    getBaseCollection: () -> FieldCollection<T>,
    getCollection: (collection: FieldCollection<T>) -> FieldCollection<T> = { it },
    forUser: suspend AuthAccessor<USER>.(collection: FieldCollection<T>) -> FieldCollection<T> = { it },
    modelName: String = serialization.serializer.descriptor.serialName.substringBefore('<').substringAfterLast('.'),
    defaultItem: suspend AuthAccessor<USER>.() -> T,
    exampleItem: ()->T? = { null },
) = object : ModelInfoWithDefault<USER, T, ID> {
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
    override suspend fun defaultItem(auth: RequestAuth<USER & Any>?): T = defaultItem(AuthAccessor<USER>(auth, null))
    override fun exampleItem(): T? = exampleItem()
}

interface ModelInfoWithDefault<USER: HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> : ModelInfo<USER, T, ID> {
    suspend fun defaultItem(auth: RequestAuth<USER & Any>?): T
    fun exampleItem(): T? = null
}
