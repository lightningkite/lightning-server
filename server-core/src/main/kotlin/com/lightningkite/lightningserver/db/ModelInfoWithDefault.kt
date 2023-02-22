package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.Database
import com.lightningkite.lightningdb.FieldCollection
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.collection
import com.lightningkite.lightningserver.auth.AuthInfo
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

inline fun <reified USER, reified T : HasId<ID>, reified ID : Comparable<ID>> ModelInfoWithDefault(
    crossinline getCollection: () -> FieldCollection<T>,
    crossinline forUser: suspend FieldCollection<T>.(user: USER) -> FieldCollection<T>,
    crossinline defaultItem: suspend (user: USER) -> T,
) = object : ModelInfoWithDefault<USER, T, ID> {
    override suspend fun defaultItem(user: USER): T = defaultItem(user)
    override fun collection(): FieldCollection<T> = getCollection()
    override suspend fun collection(user: USER): FieldCollection<T> =
        this.collection().forUser(user)

    override val serialization: ModelSerializationInfo<USER, T, ID> = ModelSerializationInfo()
}
fun <USER, T : HasId<ID>, ID : Comparable<ID>> ModelInfoWithDefault(
    serialization: ModelSerializationInfo<USER, T, ID>,
    getCollection: () -> FieldCollection<T>,
    forUser: suspend FieldCollection<T>.(user: USER) -> FieldCollection<T>,
    defaultItem: suspend (user: USER) -> T,
) = object : ModelInfoWithDefault<USER, T, ID> {
    override suspend fun defaultItem(user: USER): T = defaultItem(user)
    override fun collection(): FieldCollection<T> = getCollection()
    override suspend fun collection(user: USER): FieldCollection<T> =
        this.collection().forUser(user)

    override val serialization: ModelSerializationInfo<USER, T, ID> = serialization
}
interface ModelInfoWithDefault<USER, T : HasId<ID>, ID : Comparable<ID>>: ModelInfo<USER, T, ID> {
    abstract suspend fun defaultItem(user: USER): T
}
