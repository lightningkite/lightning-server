package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.HttpMethod
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
import com.lightningkite.services.database.PartialSerializer
import com.lightningkite.services.database.Query
import com.lightningkite.services.database.QueryPartial
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer


public open class LiveClientModelRestEndpoints<T : HasId<ID>, ID : Comparable<ID>>(
    public val fetcher: Fetcher,
    public val subpath: String,
    public val serializer: KSerializer<T>,
    public val idSerializer: KSerializer<ID>,
): ClientModelRestEndpoints<T, ID> {
    override suspend fun default(): T = fetcher(
        "$subpath/_default_",
        HttpMethod.GET,
        Unit.serializer(),
        Unit,
        serializer
    )

    override suspend fun permissions(): ModelPermissions<T> = fetcher(
        "$subpath/_permissions_",
        HttpMethod.GET,
        Unit.serializer(),
        Unit,
        ModelPermissions.serializer(serializer)
    )

    override suspend fun query(input: Query<T>): List<T> = fetcher(
        "$subpath/query",
        HttpMethod.POST,
        Query.serializer(serializer),
        input,
        ListSerializer(serializer)
    )

    override suspend fun queryPartial(input: QueryPartial<T>): List<Partial<T>> = fetcher(
        "$subpath/query-partial",
        HttpMethod.POST,
        QueryPartial.serializer(serializer),
        input,
        ListSerializer(PartialSerializer(serializer))
    )

    override suspend fun detail(id: ID): T = fetcher(
        "$subpath/${id.url()}",
        HttpMethod.GET,
        Unit.serializer(),
        Unit,
        serializer
    )

    override suspend fun insertBulk(input: List<T>): List<T> = fetcher(
        "$subpath/bulk",
        HttpMethod.POST,
        ListSerializer(serializer),
        input,
        ListSerializer(serializer)
    )

    override suspend fun insert(input: T): T = fetcher(
        "$subpath",
        HttpMethod.POST,
        serializer,
        input,
        serializer
    )

    override suspend fun upsert(id: ID, input: T): T = fetcher(
        "$subpath/${id.url()}",
        HttpMethod.POST,
        serializer,
        input,
        serializer
    )

    override suspend fun bulkReplace(input: List<T>): List<T> = fetcher(
        "$subpath",
        HttpMethod.PUT,
        ListSerializer(serializer),
        input,
        ListSerializer(serializer)
    )

    override suspend fun replace(id: ID, input: T): T = fetcher(
        "$subpath/${id.url()}",
        HttpMethod.PUT,
        serializer,
        input,
        serializer
    )

    override suspend fun bulkModify(input: MassModification<T>): Int = fetcher(
        "$subpath/bulk",
        HttpMethod.PATCH,
        MassModification.serializer(serializer),
        input,
        Int.serializer()
    )

    override suspend fun modifyWithDiff(id: ID, input: Modification<T>): EntryChange<T> = fetcher(
        "$subpath/${id.url()}/delta",
        HttpMethod.PATCH,
        Modification.serializer(serializer),
        input,
        EntryChange.serializer(serializer)
    )

    override suspend fun modify(id: ID, input: Modification<T>): T {
        return fetcher(
            "$subpath/${id.url()}",
            HttpMethod.PATCH,
            Modification.serializer(serializer),
            input,
            serializer
        )
    }

    override suspend fun bulkDelete(input: Condition<T>): Int = fetcher(
        "$subpath/bulk-delete",
        HttpMethod.POST,
        Condition.serializer(serializer),
        input,
        Int.serializer()
    )

    override suspend fun delete(id: ID): Unit = fetcher(
        "$subpath/${id.url()}",
        HttpMethod.DELETE,
        Unit.serializer(),
        Unit,
        Unit.serializer(),
    )

    override suspend fun count(input: Condition<T>): Int = fetcher(
        "$subpath/count",
        HttpMethod.POST,
        Condition.serializer(serializer),
        input,
        Int.serializer()
    )

    override suspend fun groupCount(input: GroupCountQuery<T>): Map<String, Int> = fetcher(
        "$subpath/group-count",
        HttpMethod.POST,
        GroupCountQuery.serializer(serializer),
        input,
        MapSerializer(String.serializer(), Int.serializer())
    )

    override suspend fun groupCount2(input: GroupCountQuery<T>): Map<String, Int> = fetcher(
        "$subpath/group-count-2",
        HttpMethod.POST,
        GroupCountQuery.serializer(serializer),
        input,
        MapSerializer(String.serializer(), Int.serializer())
    )

    override suspend fun aggregate(input: AggregateQuery<T>): Double? = fetcher(
        "$subpath/aggregate",
        HttpMethod.POST,
        AggregateQuery.serializer(serializer),
        input,
        Double.serializer().nullable
    )

    override suspend fun groupAggregate(input: GroupAggregateQuery<T>): Map<String, Double?> = fetcher(
        "$subpath/group-aggregate",
        HttpMethod.POST,
        GroupAggregateQuery.serializer(serializer),
        input,
        MapSerializer(String.serializer(), Double.serializer().nullable)
    )

    override suspend fun groupAggregate2(input: GroupAggregateQuery<T>): Map<String, Double?> = fetcher(
        "$subpath/group-aggregate-2",
        HttpMethod.POST,
        GroupAggregateQuery.serializer(serializer),
        input,
        MapSerializer(String.serializer(), Double.serializer().nullable)
    )
    
    private fun ID.url() = fetcher.url(this, idSerializer)
}

public open class LiveClientModelRestUpdatesWebsocket<T : HasId<ID>, ID : Comparable<ID>>(
    public val fetcher: Fetcher,
    public val subpath: String,
    public val serializer: KSerializer<T>,
    public val idSerializer: KSerializer<ID>,
) : ClientModelRestUpdatesWebsocket<T, ID> {
    override fun updates(): TypedWebSocket<Condition<T>, CollectionUpdates<T, ID>> =
        fetcher.websocket(subpath, Condition.serializer(serializer), CollectionUpdates.serializer(serializer, idSerializer))
}

public class LiveClientModelRestEndpointsAndUpdatesWebsocket<T : HasId<ID>, ID : Comparable<ID>>(
    public val fetcher: Fetcher,
    public val subpath: String,
    public val serializer: KSerializer<T>,
    public val idSerializer: KSerializer<ID>,
) : ClientModelRestEndpointsAndUpdatesWebsocket<T, ID>,
        ClientModelRestEndpoints<T, ID> by LiveClientModelRestEndpoints(fetcher, subpath, serializer, idSerializer),
        ClientModelRestUpdatesWebsocket<T, ID> by LiveClientModelRestUpdatesWebsocket(fetcher, subpath, serializer, idSerializer)