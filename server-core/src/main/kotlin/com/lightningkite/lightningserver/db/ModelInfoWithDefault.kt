package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.FieldCollection
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.RequestAuth
import com.lightningkite.lightningserver.auth.authOptions
import kotlinx.serialization.serializer

@JvmName("ModelInfoDirect")
inline fun <reified USER, reified T : HasId<ID>, reified ID : Comparable<ID>> ModelInfoWithDefault(
    crossinline getCollection: () -> FieldCollection<T>,
    crossinline forUser: suspend FieldCollection<T>.(USER) -> FieldCollection<T>,
    modelName: String = serializer<T>().descriptor.serialName.substringBefore('<').substringAfterLast('.'),
    crossinline defaultItem: suspend (user: USER) -> T,
    crossinline exampleItem: ()->T? = { null },
) = object : ModelInfoWithDefault<T, ID> {
    override val authOptions: AuthOptions = authOptions<USER>()
    override fun collection(): FieldCollection<T> = getCollection()
    override suspend fun collection(auth: RequestAuth<*>?): FieldCollection<T> =
        forUser(this.collection(), auth?.get() as USER)

    override val serialization: ModelSerializationInfo<T, ID> = ModelSerializationInfo()
    override val collectionName: String = modelName
    override suspend fun defaultItem(auth: RequestAuth<*>?): T = defaultItem(auth?.get() as USER)
    override fun exampleItem(): T? = exampleItem()
}

@JvmName("ModelInfoRequired")
inline fun <reified USER: HasId<*>, reified T : HasId<ID>, reified ID : Comparable<ID>> ModelInfoWithDefault(
    crossinline getCollection: () -> FieldCollection<T>,
    crossinline forUser: suspend FieldCollection<T>.(RequestAuth<USER>) -> FieldCollection<T>,
    modelName: String = serializer<T>().descriptor.serialName.substringBefore('<').substringAfterLast('.'),
    crossinline defaultItem2: suspend (auth: RequestAuth<USER>) -> T,
    crossinline exampleItem: ()->T? = { null },
) = object : ModelInfoWithDefault<T, ID> {
    override val authOptions: AuthOptions = authOptions<USER>()
    override fun collection(): FieldCollection<T> = getCollection()
    @Suppress("UNCHECKED_CAST")
    override suspend fun collection(auth: RequestAuth<*>?): FieldCollection<T> =
        forUser(this.collection(), auth as RequestAuth<USER>)

    override val serialization: ModelSerializationInfo<T, ID> = ModelSerializationInfo()
    override val collectionName: String = modelName
    override suspend fun defaultItem(auth: RequestAuth<*>?): T = defaultItem2(auth as RequestAuth<USER>)
    override fun exampleItem(): T? = exampleItem()
}

@JvmName("ModelInfoOptional")
inline fun <reified USER: HasId<*>, reified T : HasId<ID>, reified ID : Comparable<ID>> ModelInfoWithDefault(
    crossinline getCollection: () -> FieldCollection<T>,
    crossinline forUser: suspend FieldCollection<T>.(RequestAuth<USER>?) -> FieldCollection<T>,
    modelName: String = serializer<T>().descriptor.serialName.substringBefore('<').substringAfterLast('.'),
    crossinline defaultItem2: suspend (auth: RequestAuth<USER>?) -> T,
    crossinline exampleItem: ()->T? = { null },
) = object : ModelInfoWithDefault<T, ID> {
    override val authOptions: AuthOptions = authOptions<USER>() + setOf(null)
    override fun collection(): FieldCollection<T> = getCollection()
    @Suppress("UNCHECKED_CAST")
    override suspend fun collection(auth: RequestAuth<*>?): FieldCollection<T> =
        forUser(this.collection(), auth as RequestAuth<USER>?)

    override val serialization: ModelSerializationInfo<T, ID> = ModelSerializationInfo()
    override val collectionName: String = modelName
    override suspend fun defaultItem(auth: RequestAuth<*>?): T = defaultItem2(auth as RequestAuth<USER>?)
    override fun exampleItem(): T? = exampleItem()
}

fun <T : HasId<ID>, ID : Comparable<ID>> ModelInfoWithDefault(
    serialization: ModelSerializationInfo<T, ID>,
    authOptions: AuthOptions,
    getCollection: () -> FieldCollection<T>,
    forUser: suspend FieldCollection<T>.(principal: RequestAuth<*>?) -> FieldCollection<T>,
    modelName: String = serialization.serializer.descriptor.serialName.substringBefore('<').substringAfterLast('.'),
    defaultItem: suspend (auth: RequestAuth<*>?) -> T,
    exampleItem: ()->T? = { null },
) = object : ModelInfoWithDefault<T, ID> {
    override val authOptions: AuthOptions = authOptions
    override val serialization: ModelSerializationInfo<T, ID> = serialization
    override fun collection(): FieldCollection<T> = getCollection()
    override suspend fun collection(auth: RequestAuth<*>?): FieldCollection<T> =
        this.collection().forUser(auth)

    override val collectionName: String = modelName
    override suspend fun defaultItem(auth: RequestAuth<*>?): T = defaultItem(auth)
    override fun exampleItem(): T? = exampleItem()
}

interface ModelInfoWithDefault<T : HasId<ID>, ID : Comparable<ID>> : ModelInfo<T, ID> {
    suspend fun defaultItem(auth: RequestAuth<*>?): T
    fun exampleItem(): T? = null
}
