package com.lightningkite.lightningserver.cassandra

import com.datastax.oss.driver.api.core.CqlIdentifier
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.querybuilder.QueryBuilder.*
import com.datastax.oss.driver.api.querybuilder.*
import com.datastax.oss.driver.api.querybuilder.SchemaBuilder.*
import com.datastax.oss.driver.api.core.cql.*
import com.datastax.oss.driver.api.core.metadata.schema.ClusteringOrder
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata
import com.datastax.oss.driver.api.core.metadata.schema.IndexKind
import com.datastax.oss.driver.api.core.metadata.schema.IndexMetadata
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata
import com.datastax.oss.driver.api.core.type.DataType
import com.datastax.oss.driver.api.querybuilder.schema.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.future.await


suspend fun CqlSession.executeSuspending(query: String) = executeAsync(query).await()
suspend fun CqlSession.executeSuspending(statement: Statement<*>) = executeAsync(statement).await()
suspend fun CqlSession.executeSuspending(query: String, vararg values: Any?) = executeAsync(query, *values).await()
suspend fun CqlSession.executeSuspending(query: String, values: Map<String, Any?>) = executeAsync(query, values).await()

suspend fun CqlSession.prepareSuspending(query: String) = prepareAsync(query).await()
suspend fun CqlSession.prepareSuspending(statement: SimpleStatement) = prepareAsync(statement).await()
suspend fun CqlSession.prepareSuspending(request: PrepareRequest) = prepareAsync(request).await()

suspend fun AsyncResultSet.asFlow() = flow<Row> {
    var c = this@asFlow
    c.currentPage().forEach { emit(it) }
    while (hasMorePages()) {
        c = fetchNextPage().await()
        c.currentPage().forEach { emit(it) }
    }
}

data class LsCqlDesiredTable(
    val keyspace: CqlIdentifier,
    val name: CqlIdentifier,
    val columns: List<LsCqlDesiredColumn>,
    val indexes: List<LsCqlDesiredIndex>,
    val partitionKey: List<LsCqlDesiredColumn>,
    val clusteringColumns: Map<LsCqlDesiredColumn, ClusteringOrder>,
    val isCompactStorage: Boolean = false,
    val isVirtual: Boolean = false,
) {
    constructor(meta: TableMetadata) : this(
        keyspace = meta.keyspace,
        name = meta.name,
        columns = meta.columns.map { LsCqlDesiredColumn(it) },
        indexes = meta.indexes.map { LsCqlDesiredIndex(it) },
        partitionKey = meta.partitionKey.map { LsCqlDesiredColumn(it) },
        clusteringColumns = meta.clusteringColumns.mapKeys { LsCqlDesiredColumn(it.key) },
        isCompactStorage = meta.isCompactStorage,
        isVirtual = meta.isVirtual,
    )
}

data class LsCqlDesiredColumn(
    val name: CqlIdentifier,
    val type: DataType
) {
    constructor(meta: ColumnMetadata) : this(
        name = meta.name,
        type = meta.type,
    )
}

data class LsCqlDesiredIndex(
    val name: CqlIdentifier,
    val kind: IndexKind,
    val target: String,
    val options: Map<String, String>
) {
    constructor(meta: IndexMetadata) : this(
        name = meta.name,
        kind = meta.kind,
        target = meta.target,
        options = meta.options
    )
}

suspend fun CqlSession.updateSchema(from: LsCqlDesiredTable?, to: LsCqlDesiredTable) {
    if (from == null) {
        executeSuspending(
            createTable(to.keyspace, to.name).ifNotExists().let {
                var x: OngoingPartitionKey = it
                to.partitionKey.forEach { k ->
                    x = x.withPartitionKey(k.name, k.type)
                }
                x as CreateTable
            }.let {
                var x = it
                to.clusteringColumns.forEach { (k, o) ->
                    x = x.withClusteringColumn(k.name, k.type)
                }
                x
            }.let {
                var x = it
                to.columns.forEach { c ->
                    x = x.withColumn(c.name, c.type)
                }
                x
            }.let {
                var x = it as CreateTableWithOptions
                to.clusteringColumns.forEach { (k, o) ->
                    x = x.withClusteringOrder(k.name, o)
                }
                x
            }
                .let {
                    if(to.isCompactStorage) it.withCompactStorage()
                    else it
                }
                .build()
        )
    } else {
        val newColumns = (to.columns.map { it.name }.toSet() - from.columns.map { it.name }.toSet()).map { to.columns.find { c -> it == c.name }!! }
        val oldColumns = (from.columns.map { it.name }.toSet() - to.columns.map { it.name }.toSet()).map { from.columns.find { c -> it == c.name }!! }
        val changedColumns = (to.columns.filter {
            val old = from.columns.find { c -> c.name == it.name }
            old != null && old.type != it.type
        })
        if(changedColumns.isNotEmpty()) throw Exception("Cannot change a column's type without blowing something up!")
        // Add columns
        executeSuspending(alterTable(to.keyspace, to.name).let {
            var x = it as AlterTableAddColumnEnd
            newColumns.forEach { c ->
                x = x.addColumn(c.name, c.type)
            }
            x
        }.build())
        // TODO: Drop columns?

    }
    // Update indicies
    val newIndexes = to.indexes.toSet() - (from?.indexes?.toSet() ?: setOf())
    val oldIndexes = (from?.indexes?.toSet() ?: setOf()) - to.indexes.toSet()
    for(index in oldIndexes) {
        executeSuspending(dropIndex(index.name).build())
    }
    for(index in newIndexes) {
        executeSuspending(createIndex().onTable(index.name).let {
            val x = it
            index.target.forEach {

            }
            x
        })
    }
}