package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.FieldCollection
import com.lightningkite.lightningdb.HasId
import kotlinx.serialization.serializer

inline fun <reified USER, reified T : HasId<ID>, reified ID : Comparable<ID>> ModelInfoWithDefault(
    crossinline getCollection: () -> FieldCollection<T>,
    crossinline forUser: suspend FieldCollection<T>.(user: USER) -> FieldCollection<T>,
    crossinline defaultItem: suspend (user: USER) -> T,
    crossinline exampleItem: ()->T? = { null },
    modelName: String = serializer<T>().descriptor.serialName.substringBefore('<').substringAfterLast('.')
) = object : ModelInfoWithDefault<USER, T, ID> {
    override val collectionName: String = modelName
    override suspend fun defaultItem(user: USER): T = defaultItem(user)
    override fun collection(): FieldCollection<T> = getCollection()
    override suspend fun collection(user: USER): FieldCollection<T> =
        this.collection().forUser(user)

    override val serialization: ModelSerializationInfo<USER, T, ID> = ModelSerializationInfo()
    override fun exampleItem(): T? = exampleItem()
}

fun <USER, T : HasId<ID>, ID : Comparable<ID>> ModelInfoWithDefault(
    serialization: ModelSerializationInfo<USER, T, ID>,
    getCollection: () -> FieldCollection<T>,
    forUser: suspend FieldCollection<T>.(user: USER) -> FieldCollection<T>,
    defaultItem: suspend (user: USER) -> T,
    exampleItem: ()->T? = { null },
    modelName: String = serialization.serializer.descriptor.serialName.substringBefore('<').substringAfterLast('.')
) = object : ModelInfoWithDefault<USER, T, ID> {
    override val collectionName: String = modelName
    override suspend fun defaultItem(user: USER): T = defaultItem(user)
    override fun collection(): FieldCollection<T> = getCollection()
    override suspend fun collection(user: USER): FieldCollection<T> =
        this.collection().forUser(user)

    override val serialization: ModelSerializationInfo<USER, T, ID> = serialization
    override fun exampleItem(): T? = exampleItem()
}

interface ModelInfoWithDefault<USER, T : HasId<ID>, ID : Comparable<ID>> : ModelInfo<USER, T, ID> {
    suspend fun defaultItem(user: USER): T
    fun exampleItem(): T? = null
}
