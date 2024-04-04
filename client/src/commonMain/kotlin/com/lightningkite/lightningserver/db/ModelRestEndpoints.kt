package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.kiteui.TypedWebSocket
import com.lightningkite.kiteui.reactive.*
import kotlinx.serialization.KSerializer

interface ModelRestEndpoints<T : HasId<ID>, ID : Comparable<ID>> {
    suspend fun default(): T = throw IllegalArgumentException()
    suspend fun query(input: Query<T>): List<T>
    suspend fun queryPartial(input: QueryPartial<T>): List<Partial<T>>
    suspend fun detail(id: ID): T
    suspend fun insertBulk(input: List<T>): List<T>
    suspend fun insert(input: T): T
    suspend fun upsert(id: ID, input: T): T
    suspend fun bulkReplace(input: List<T>): List<T>
    suspend fun replace(id: ID, input: T): T
    suspend fun bulkModify(input: MassModification<T>): Int
    suspend fun modifyWithDiff(id: ID, input: Modification<T>): EntryChange<T>
    suspend fun modify(id: ID, input: Modification<T>): T
    suspend fun bulkDelete(input: Condition<T>): Int
    suspend fun delete(id: ID): Unit
    suspend fun count(input: Condition<T>): Int
    suspend fun groupCount(input: GroupCountQuery<T>): Map<String, Int>
    suspend fun aggregate(input: AggregateQuery<T>): Double?
    suspend fun groupAggregate(input: GroupAggregateQuery<T>): Map<String, Double?>
}

interface ModelRestEndpointsPlusWs<T : HasId<ID>, ID : Comparable<ID>> : ModelRestEndpoints<T, ID> {
    suspend fun watch(): TypedWebSocket<Query<T>, ListChange<T>>
}

interface ModelRestEndpointsPlusUpdatesWebsocket<T : HasId<ID>, ID : Comparable<ID>> : ModelRestEndpoints<T, ID> {
    suspend fun updates(): TypedWebSocket<Condition<T>, CollectionUpdates<T, ID>>
}

interface WritableModel<T> : Writable<T?> {
    val serializer: KSerializer<T>
    suspend fun modify(modification: Modification<T>): T?
    suspend fun delete(): Unit
    fun invalidate(): Unit
}

interface ModelCollection<T : HasId<ID>, ID : Comparable<ID>> {
    operator fun get(id: ID): WritableModel<T>
    suspend fun query(query: Query<T>): Readable<List<T>>
    suspend fun watch(query: Query<T>): Readable<List<T>>
    fun watch(id: ID): WritableModel<T> = get(id)
    suspend fun insert(item: T): WritableModel<T>
    suspend fun insert(item: List<T>): List<T>
    suspend fun upsert(item: T): WritableModel<T>
    suspend fun bulkModify(bulkUpdate: MassModification<T>): Int
}

interface CachingModelRestEndpoints<T : HasId<ID>, ID : Comparable<ID>> : ModelCollection<T, ID> {
    val skipCache: ModelRestEndpoints<T, ID>
    fun totallyInvalidate()
}

