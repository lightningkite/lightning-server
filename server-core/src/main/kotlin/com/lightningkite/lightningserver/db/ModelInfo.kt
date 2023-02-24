package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.FieldCollection
import com.lightningkite.lightningdb.HasId
import kotlinx.serialization.serializer

inline fun <reified USER, reified T : HasId<ID>, reified ID : Comparable<ID>> ModelInfo(
    crossinline getCollection: () -> FieldCollection<T>,
    crossinline forUser: suspend FieldCollection<T>.(principal: USER) -> FieldCollection<T>,
    modelName: String = serializer<T>().descriptor.serialName.substringBefore('<').substringAfterLast('.')
) = object : ModelInfo<USER, T, ID> {
    override fun collection(): FieldCollection<T> = getCollection()
    override suspend fun collection(principal: USER): FieldCollection<T> =
        this.collection().forUser(principal)
    override val serialization: ModelSerializationInfo<USER, T, ID> = ModelSerializationInfo()
    override val collectionName: String = modelName
}

fun <USER, T : HasId<ID>, ID : Comparable<ID>> ModelInfo(
    serialization: ModelSerializationInfo<USER, T, ID>,
    getCollection: () -> FieldCollection<T>,
    forUser: suspend FieldCollection<T>.(principal: USER) -> FieldCollection<T>,
    modelName: String = serialization.serializer.descriptor.serialName.substringBefore('<').substringAfterLast('.')
) = object : ModelInfo<USER, T, ID> {
    override val serialization: ModelSerializationInfo<USER, T, ID> = serialization
    override fun collection(): FieldCollection<T> = getCollection()
    override suspend fun collection(principal: USER): FieldCollection<T> =
        this.collection().forUser(principal)

    override val collectionName: String = modelName
}

interface ModelInfo<USER, T : HasId<ID>, ID : Comparable<ID>> {
    val serialization: ModelSerializationInfo<USER, T, ID>
    val collectionName: String get() = serialization.serializer.descriptor.serialName.substringBefore('<').substringAfterLast('.')
    fun collection(): FieldCollection<T>
    suspend fun collection(principal: USER): FieldCollection<T>
}

