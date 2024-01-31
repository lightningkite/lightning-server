package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils.statementsRequiredToActualizeScheme
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.sql.Connection.TRANSACTION_READ_COMMITTED
import java.sql.Connection.TRANSACTION_SERIALIZABLE

class PostgresCollection<T : Any>(
    val db: Database,
    val name: String,
    override val serializer: KSerializer<T>,
) : FieldCollection<T> {
    companion object {
        var format = DbMapLikeFormat(Serialization.module)
    }

    val table = SerialDescriptorTable(name, serializer.descriptor)

    suspend inline fun <T> t(noinline action: suspend Transaction.() -> T): T =
        newSuspendedTransaction(Dispatchers.IO, db = db, transactionIsolation = TRANSACTION_READ_COMMITTED, action)

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
                .select { condition(condition, serializer, table).asOp() }
                .orderBy(*orderBy.map { (if (it.ignoreCase && it.field.serializerAny.descriptor.kind == PrimitiveKind.STRING) (table.col[it.field.colName]!! as Column<String>).lowerCase() else table.col[it.field.colName]!!) to if (it.ascending) SortOrder.ASC else SortOrder.DESC }
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
            table
                .select { condition(condition, serializer, table).asOp() }
                .count().toInt()
        }
    }

    override suspend fun <Key> groupCount(condition: Condition<T>, groupBy: DataClassPath<T, Key>): Map<Key, Int> {
        prepare.await()
        return t {
            val groupCol = table.col[groupBy.colName] as Column<Key>
            val count = Count(stringLiteral("*"))
            table.slice(groupCol, count)
                .select { condition(condition, serializer, table).asOp() }
                .groupBy(table.col[groupBy.colName]!!).associate { it[groupCol] to it[count].toInt() }
        }
    }

    override suspend fun <N : Number?> aggregate(
        aggregate: Aggregate,
        condition: Condition<T>,
        property: DataClassPath<T, N>,
    ): Double? {
        prepare.await()
        return t {
            val valueCol = table.col[property.colName] as Column<Number>
            val agg = when (aggregate) {
                Aggregate.Sum -> Sum(valueCol, DecimalColumnType(Int.MAX_VALUE, 8))
                Aggregate.Average -> Avg<Double, Double>(valueCol, 8)
                Aggregate.StandardDeviationSample -> StdDevSamp(valueCol, 8)
                Aggregate.StandardDeviationPopulation -> StdDevPop(valueCol, 8)
            }
            table.slice(agg)
                .select { condition(condition, serializer, table).asOp() }
                .firstOrNull()?.get(agg)?.toDouble()
        }
    }

    override suspend fun <N : Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<T>,
        groupBy: DataClassPath<T, Key>,
        property: DataClassPath<T, N>,
    ): Map<Key, Double?> {
        prepare.await()
        return t {
            val groupCol = table.col[groupBy.colName] as Column<Key>
            val valueCol = table.col[property.colName] as Column<Number>
            val agg = when (aggregate) {
                Aggregate.Sum -> Sum(valueCol, DoubleColumnType())
                Aggregate.Average -> Avg<Double, Double>(valueCol, 8)
                Aggregate.StandardDeviationSample -> StdDevSamp(valueCol, 8)
                Aggregate.StandardDeviationPopulation -> StdDevPop(valueCol, 8)
            }
            table.slice(groupCol, agg)
                .select { condition(condition, serializer, table).asOp() }
                .groupBy(table.col[groupBy.colName]!!).associate { it[groupCol] to it[agg]?.toDouble() }
        }
    }

    override suspend fun insert(models: Iterable<T>): List<T> {
        prepare.await()
        t {
            table.batchInsert(models) {
                format.encode(serializer, it, this)
            }
        }
        return models.toList()
    }

    override suspend fun replaceOne(condition: Condition<T>, model: T, orderBy: List<SortPart<T>>): EntryChange<T> {
        return updateOne(condition, Modification.Assign(model), orderBy)
    }

    override suspend fun replaceOneIgnoringResult(
        condition: Condition<T>,
        model: T,
        orderBy: List<SortPart<T>>
    ): Boolean {
        return updateOneIgnoringResult(condition, Modification.Assign(model), orderBy)
    }

    override suspend fun upsertOne(
        condition: Condition<T>,
        modification: Modification<T>,
        model: T
    ): EntryChange<T> {
        return newSuspendedTransaction(db = db, transactionIsolation = TRANSACTION_SERIALIZABLE) {
            val existing = findOne(condition)
            if (existing == null) {
                EntryChange(null, insert(listOf(model)).first())
            } else
                updateOne(condition, modification)
        }
    }

    override suspend fun upsertOneIgnoringResult(
        condition: Condition<T>,
        modification: Modification<T>,
        model: T,
    ): Boolean {
        return newSuspendedTransaction(db = db, transactionIsolation = TRANSACTION_SERIALIZABLE) {
            val existing = findOne(condition)
            if (existing == null) {
                insert(listOf(model))
                false
            } else
                updateOneIgnoringResult(condition, modification, listOf())
        }
    }

    override suspend fun updateOne(
        condition: Condition<T>,
        modification: Modification<T>,
        orderBy: List<SortPart<T>>
    ): EntryChange<T> {
        if (orderBy.isNotEmpty()) throw UnsupportedOperationException()
        return t {
            val old = table.updateReturningOld(
                where = { condition(condition, serializer, table).asOp() },
                limit = 1,
                body = {
                    it.modification(modification, serializer, table)
                }
            )
            old.map { format.decode(serializer, it) }.firstOrNull()?.let {
                EntryChange(it, modification(it))
            } ?: EntryChange()
        }
    }

    override suspend fun updateOneIgnoringResult(
        condition: Condition<T>,
        modification: Modification<T>,
        orderBy: List<SortPart<T>>
    ): Boolean {
        if (orderBy.isNotEmpty()) throw UnsupportedOperationException()
        return t {
            table.update(
                where = { condition(condition, serializer, table).asOp() },
                limit = null,
                body = {
                    it.modification(modification, serializer, table)
                }
            )
        } > 0
    }

    override suspend fun updateMany(condition: Condition<T>, modification: Modification<T>): CollectionChanges<T> {
        return t {
            val old = table.updateReturningOld(
                where = { condition(condition, serializer, table).asOp() },
                limit = null,
                body = {
                    it.modification(modification, serializer, table)
                }
            )
            CollectionChanges(old.map { format.decode(serializer, it) }.map {
                EntryChange(it, modification(it))
            })
        }
    }

    override suspend fun updateManyIgnoringResult(condition: Condition<T>, modification: Modification<T>): Int {
        return t {
            table.update(
                where = { condition(condition, serializer, table).asOp() },
                limit = null,
                body = {
                    it.modification(modification, serializer, table)
                }
            )
        }
    }

    override suspend fun deleteOne(condition: Condition<T>, orderBy: List<SortPart<T>>): T? {
        if (orderBy.isNotEmpty()) throw UnsupportedOperationException()
        return t {
            table.deleteReturningWhere(
                limit = 1,
                where = { condition(condition, serializer, table).asOp() }
            ).firstOrNull()?.let { format.decode(serializer, it) }
        }
    }

    override suspend fun deleteOneIgnoringOld(condition: Condition<T>, orderBy: List<SortPart<T>>): Boolean {
        if (orderBy.isNotEmpty()) throw UnsupportedOperationException()
        return t {
            table.deleteWhere(
                limit = 1,
                op = { condition(condition, serializer, table).asOp() }
            ) > 0
        }
    }

    override suspend fun deleteMany(condition: Condition<T>): List<T> {
        return t {
            table.deleteReturningWhere(
                where = { condition(condition, serializer, table).asOp() }
            ).map { format.decode(serializer, it) }
        }
    }

    override suspend fun deleteManyIgnoringOld(condition: Condition<T>): Int {
        return t {
            table.deleteWhere(
                op = { condition(condition, serializer, table).asOp() }
            )
        }
    }

}