package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils.statementsRequiredToActualizeScheme
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.sql.Connection.TRANSACTION_READ_COMMITTED
import kotlin.reflect.KProperty1

class PostgresCollection<T : Any>(
    val db: Database,
    val name: String,
    val serializer: KSerializer<T>,
) : FieldCollection<T> {
    companion object {
        var format = DbMapLikeFormat(Serialization.module)
    }

    val table = SerialDescriptorTable(name, serializer.descriptor)

    suspend inline fun <T> t(noinline action: suspend Transaction.()->T): T = newSuspendedTransaction(Dispatchers.IO, db = db, transactionIsolation = TRANSACTION_READ_COMMITTED, action)

    @OptIn(DelicateCoroutinesApi::class, ExperimentalSerializationApi::class)
    val prepare = GlobalScope.async(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {
        t {
            statementsRequiredToActualizeScheme(table).forEach {
                exec(it)
            }
        }
    }

    override suspend fun find(
        condition: Condition<T>,
        orderBy: List<SortPart<T>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long,
    ): Flow<T> {
        prepare.await()
        val items = t {
            table
                .select {
                    when (val c = condition(condition, serializer, table)) {
                        is Op -> c
                        else -> c.eq(LiteralOp(BooleanColumnType(), true))
                    }
                }
                .orderBy(*orderBy.map { table.col[it.field.property.name]!! to if (it.ascending) SortOrder.ASC else SortOrder.DESC }
                    .toTypedArray())
                .limit(limit, skip.toLong())
//                .prep
                .map { format.decode(serializer, it) }
        }
        return items.asFlow()
    }

    override suspend fun count(condition: Condition<T>): Int {
        prepare.await()
        return t {
            table.select {
                when (val c = condition(condition, serializer, table)) {
                    is Op -> c
                    else -> c.eq(LiteralOp(BooleanColumnType(), true))
                }
            }.count().toInt()
        }
    }

    override suspend fun <Key> groupCount(condition: Condition<T>, groupBy: KProperty1<T, Key>): Map<Key, Int> {
        prepare.await()
        return t {
            val groupCol = table.col[groupBy.name] as Column<Key>
            val count = Count(stringLiteral("*"))
            table.slice(groupCol, count).select {
                when (val c = condition(condition, serializer, table)) {
                    is Op -> c
                    else -> c.eq(LiteralOp(BooleanColumnType(), true))
                }
            }.groupBy(table.col[groupBy.name]!!).associate { it[groupCol] to it[count].toInt() }
        }
    }

    override suspend fun <N : Number> aggregate(
        aggregate: Aggregate,
        condition: Condition<T>,
        property: KProperty1<T, N>,
    ): Double? {
        prepare.await()
        return t {
            val valueCol = table.col[property.name] as Column<Number>
            val agg = when(aggregate) {
                Aggregate.Sum -> Sum(valueCol, DecimalColumnType(Int.MAX_VALUE, 8))
                Aggregate.Average -> Avg<Double, Double>(valueCol, 8)
                Aggregate.StandardDeviationSample -> StdDevSamp(valueCol, 8)
                Aggregate.StandardDeviationPopulation -> StdDevPop(valueCol, 8)
            }
            table.slice(agg).select {
                when (val c = condition(condition, serializer, table)) {
                    is Op -> c
                    else -> c.eq(LiteralOp(BooleanColumnType(), true))
                }
            }.firstOrNull()?.get(agg)?.toDouble()
        }
    }

    override suspend fun <N : Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<T>,
        groupBy: KProperty1<T, Key>,
        property: KProperty1<T, N>,
    ): Map<Key, Double?> {
        prepare.await()
        return t {
            val groupCol = table.col[groupBy.name] as Column<Key>
            val valueCol = table.col[property.name] as Column<Number>
            val agg = when(aggregate) {
                Aggregate.Sum -> Sum(valueCol, DoubleColumnType())
                Aggregate.Average -> Avg<Double, Double>(valueCol, 8)
                Aggregate.StandardDeviationSample -> StdDevSamp(valueCol, 8)
                Aggregate.StandardDeviationPopulation -> StdDevPop(valueCol, 8)
            }
            table.slice(groupCol, agg).select {
                when (val c = condition(condition, serializer, table)) {
                    is Op -> c
                    else -> c.eq(LiteralOp(BooleanColumnType(), true))
                }
            }.groupBy(table.col[groupBy.name]!!).associate { it[groupCol] to it[agg]?.toDouble() }
        }
    }

    override suspend fun insert(models: List<T>): List<T> {
        prepare.await()
        t {
            table.batchInsert(models) {
                format.encode(serializer, it, this)
            }
        }
        return models
    }

    override suspend fun replaceOne(condition: Condition<T>, model: T): EntryChange<T> {
        TODO("Not yet implemented")
    }

    override suspend fun replaceOneIgnoringResult(condition: Condition<T>, model: T): Boolean {
        TODO("Not yet implemented")
    }

    override suspend fun upsertOne(condition: Condition<T>, modification: Modification<T>, model: T): EntryChange<T> {
        TODO("Not yet implemented")
    }

    override suspend fun upsertOneIgnoringResult(
        condition: Condition<T>,
        modification: Modification<T>,
        model: T,
    ): Boolean {
        TODO("Not yet implemented")
    }

    override suspend fun updateOne(condition: Condition<T>, modification: Modification<T>): EntryChange<T> {
        TODO("Not yet implemented")
    }

    override suspend fun updateOneIgnoringResult(condition: Condition<T>, modification: Modification<T>): Boolean {
        TODO("Not yet implemented")
    }

    override suspend fun updateMany(condition: Condition<T>, modification: Modification<T>): CollectionChanges<T> {
        TODO("Not yet implemented")
    }

    override suspend fun updateManyIgnoringResult(condition: Condition<T>, modification: Modification<T>): Int {
        TODO("Not yet implemented")
    }

    override suspend fun deleteOne(condition: Condition<T>): T? {
        TODO("Not yet implemented")
    }

    override suspend fun deleteOneIgnoringOld(condition: Condition<T>): Boolean {
        TODO("Not yet implemented")
    }

    override suspend fun deleteMany(condition: Condition<T>): List<T> {
        TODO("Not yet implemented")
    }

    override suspend fun deleteManyIgnoringOld(condition: Condition<T>): Int {
        TODO("Not yet implemented")
    }

    override fun registerRawSignal(callback: suspend (CollectionChanges<T>) -> Unit) {
        TODO("Not yet implemented")
    }

}