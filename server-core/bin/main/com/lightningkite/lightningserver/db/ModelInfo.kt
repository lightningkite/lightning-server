package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.FieldCollection
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.auth.AuthInfo
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

inline fun <reified USER, reified T : HasId<ID>, reified ID : Comparable<ID>> ModelInfo(
    crossinline getCollection: () -> FieldCollection<T>,
    crossinline forUser: suspend FieldCollection<T>.(principal: USER) -> FieldCollection<T>
) = object : ModelInfo<USER, T, ID> {
    override fun collection(): FieldCollection<T> = getCollection()
    override suspend fun collection(principal: USER): FieldCollection<T> =
        this.collection().forUser(principal)
    override val serialization: ModelSerializationInfo<USER, T, ID> = ModelSerializationInfo()
}

fun <USER, T : HasId<ID>, ID : Comparable<ID>> ModelInfo(
    serialization: ModelSerializationInfo<USER, T, ID>,
    getCollection: () -> FieldCollection<T>,
    forUser: suspend FieldCollection<T>.(principal: USER) -> FieldCollection<T>,
) = object : ModelInfo<USER, T, ID> {
    override val serialization: ModelSerializationInfo<USER, T, ID> = serialization
    override fun collection(): FieldCollection<T> = getCollection()
    override suspend fun collection(principal: USER): FieldCollection<T> =
        this.collection().forUser(principal)
}

interface ModelInfo<USER, T : HasId<ID>, ID : Comparable<ID>> {
    val serialization: ModelSerializationInfo<USER, T, ID>
    fun collection(): FieldCollection<T>
    suspend fun collection(principal: USER): FieldCollection<T>
}

