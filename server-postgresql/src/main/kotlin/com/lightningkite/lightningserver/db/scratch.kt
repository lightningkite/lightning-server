package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.reflect.KProperty1

class PostgresCollection<T: Any>(
    val db: Database,
    val serializer: KSerializer<T>
): FieldCollection<T> {
    val table = SerialDescriptorTable(serializer.descriptor)

    override suspend fun find(
        condition: Condition<T>,
        orderBy: List<SortPart<T>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long,
    ): Flow<T> = TODO()

    override suspend fun count(condition: Condition<T>): Int {
        TODO("Not yet implemented")
    }

    override suspend fun <Key> groupCount(condition: Condition<T>, groupBy: KProperty1<T, Key>): Map<Key, Int> {
        TODO("Not yet implemented")
    }

    override suspend fun <N : Number> aggregate(
        aggregate: Aggregate,
        condition: Condition<T>,
        property: KProperty1<T, N>,
    ): Double? {
        TODO("Not yet implemented")
    }

    override suspend fun <N : Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<T>,
        groupBy: KProperty1<T, Key>,
        property: KProperty1<T, N>,
    ): Map<Key, Double?> {
        TODO("Not yet implemented")
    }

    override suspend fun insert(models: List<T>): List<T> {
        TODO("Not yet implemented")
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