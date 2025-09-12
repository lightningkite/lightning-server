package com.lightningkite.lightningserver.typed

import com.lightningkite.services.database.AggregateQuery
import com.lightningkite.services.database.CollectionUpdates
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.EntryChange
import com.lightningkite.services.database.GroupAggregateQuery
import com.lightningkite.services.database.GroupCountQuery
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.MassModification
import com.lightningkite.services.database.ModelPermissions
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.Partial
import com.lightningkite.services.database.Query
import com.lightningkite.services.database.QueryPartial

@LiveVersion(LiveClientModelRestEndpoints::class)
public interface ClientModelRestEndpoints<T : HasId<ID>, ID : Comparable<ID>> {
    public suspend fun default(): T = throw IllegalArgumentException()
    public suspend fun query(input: Query<T>): List<T>
    public suspend fun queryPartial(input: QueryPartial<T>): List<Partial<T>>
    public suspend fun detail(id: ID): T
    public suspend fun insertBulk(input: List<T>): List<T>
    public suspend fun insert(input: T): T
    public suspend fun upsert(id: ID, input: T): T
    public suspend fun bulkReplace(input: List<T>): List<T>
    public suspend fun replace(id: ID, input: T): T
    public suspend fun bulkModify(input: MassModification<T>): Int
    public suspend fun modifyWithDiff(id: ID, input: Modification<T>): EntryChange<T>
    public suspend fun modify(id: ID, input: Modification<T>): T
    public suspend fun bulkDelete(input: Condition<T>): Int
    public suspend fun delete(id: ID): Unit
    public suspend fun count(input: Condition<T>): Int
    public suspend fun groupCount(input: GroupCountQuery<T>): Map<String, Int>
    public suspend fun groupCount2(input: GroupCountQuery<T>): Map<String, Int> = groupCount(input)
    public suspend fun aggregate(input: AggregateQuery<T>): Double?
    public suspend fun groupAggregate(input: GroupAggregateQuery<T>): Map<String, Double?>
    public suspend fun groupAggregate2(input: GroupAggregateQuery<T>): Map<String, Double?> = groupAggregate(input)
    public suspend fun permissions(): ModelPermissions<T>
}

@LiveVersion(LiveClientModelRestUpdatesWebsocket::class)
public interface ClientModelRestUpdatesWebsocket<T : HasId<ID>, ID : Comparable<ID>> {
    public fun updates(): TypedWebSocket<Condition<T>, CollectionUpdates<T, ID>>
}

@LiveVersion(LiveClientModelRestEndpointsAndUpdatesWebsocket::class)
public interface ClientModelRestEndpointsAndUpdatesWebsocket<T : HasId<ID>, ID : Comparable<ID>> : ClientModelRestEndpoints<T, ID>, ClientModelRestUpdatesWebsocket<T, ID>