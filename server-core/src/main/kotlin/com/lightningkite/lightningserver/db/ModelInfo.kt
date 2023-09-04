package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.FieldCollection
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.RequestAuth
import com.lightningkite.lightningserver.auth.authOptions
import kotlinx.serialization.serializer

@JvmName("ModelInfoDirect")
inline fun <reified USER, reified T : HasId<ID>, reified ID : Comparable<ID>> ModelInfo(
    crossinline getCollection: () -> FieldCollection<T>,
    crossinline forUser: suspend FieldCollection<T>.(USER) -> FieldCollection<T>,
    modelName: String = serializer<T>().descriptor.serialName.substringBefore('<').substringAfterLast('.')
) = object : ModelInfo<T, ID> {
    override val authOptions: AuthOptions = authOptions<USER>()
    override fun collection(): FieldCollection<T> = getCollection()
    override suspend fun collection(auth: RequestAuth<*>?): FieldCollection<T> =
        forUser(this.collection(), auth?.get() as USER)

    override val serialization: ModelSerializationInfo<T, ID> = ModelSerializationInfo()
    override val collectionName: String = modelName
}

@JvmName("ModelInfoRequired")
inline fun <reified USER: HasId<*>, reified T : HasId<ID>, reified ID : Comparable<ID>> ModelInfo(
    crossinline getCollection: () -> FieldCollection<T>,
    crossinline forUser: suspend FieldCollection<T>.(RequestAuth<USER>) -> FieldCollection<T>,
    modelName: String = serializer<T>().descriptor.serialName.substringBefore('<').substringAfterLast('.')
) = object : ModelInfo<T, ID> {
    override val authOptions: AuthOptions = authOptions<USER>()
    override fun collection(): FieldCollection<T> = getCollection()
    @Suppress("UNCHECKED_CAST")
    override suspend fun collection(auth: RequestAuth<*>?): FieldCollection<T> =
        forUser(this.collection(), auth as RequestAuth<USER>)

    override val serialization: ModelSerializationInfo<T, ID> = ModelSerializationInfo()
    override val collectionName: String = modelName
}

@JvmName("ModelInfoOptional")
inline fun <reified USER: HasId<*>, reified T : HasId<ID>, reified ID : Comparable<ID>> ModelInfo(
    crossinline getCollection: () -> FieldCollection<T>,
    crossinline forUser: suspend FieldCollection<T>.(RequestAuth<USER>?) -> FieldCollection<T>,
    modelName: String = serializer<T>().descriptor.serialName.substringBefore('<').substringAfterLast('.')
) = object : ModelInfo<T, ID> {
    override val authOptions: AuthOptions = authOptions<USER>() + setOf(null)
    override fun collection(): FieldCollection<T> = getCollection()
    @Suppress("UNCHECKED_CAST")
    override suspend fun collection(auth: RequestAuth<*>?): FieldCollection<T> =
        forUser(this.collection(), auth as RequestAuth<USER>?)

    override val serialization: ModelSerializationInfo<T, ID> = ModelSerializationInfo()
    override val collectionName: String = modelName
}

fun <T : HasId<ID>, ID : Comparable<ID>> ModelInfo(
    serialization: ModelSerializationInfo<T, ID>,
    authOptions: AuthOptions,
    getCollection: () -> FieldCollection<T>,
    forUser: suspend FieldCollection<T>.(principal: RequestAuth<*>?) -> FieldCollection<T>,
    modelName: String = serialization.serializer.descriptor.serialName.substringBefore('<').substringAfterLast('.')
) = object : ModelInfo<T, ID> {
    override val authOptions: AuthOptions = authOptions
    override val serialization: ModelSerializationInfo<T, ID> = serialization
    override fun collection(): FieldCollection<T> = getCollection()
    override suspend fun collection(auth: RequestAuth<*>?): FieldCollection<T> =
        this.collection().forUser(auth)

    override val collectionName: String = modelName
}

interface ModelInfo<T : HasId<ID>, ID : Comparable<ID>> {
    val serialization: ModelSerializationInfo<T, ID>
    val authOptions: AuthOptions
    val collectionName: String
        get() = serialization.serializer.descriptor.serialName.substringBefore('<').substringAfterLast('.')

    fun collection(): FieldCollection<T>
    suspend fun collection(auth: RequestAuth<*>?): FieldCollection<T>
}


