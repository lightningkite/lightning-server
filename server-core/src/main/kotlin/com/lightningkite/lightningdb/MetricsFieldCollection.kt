package com.lightningkite.lightningdb

import com.lightningkite.lightningserver.metrics.MetricType
import com.lightningkite.lightningserver.metrics.MetricUnit
import com.lightningkite.lightningserver.metrics.Metrics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer

class MetricsFieldCollection<M: Any>(override val wraps: FieldCollection<M>, metricsKeyName: String = "Database"): FieldCollection<M> {
    override val serializer: KSerializer<M> get() = wraps.serializer
    val metricsKey = MetricType("$metricsKeyName Wait Time", MetricUnit.Milliseconds)
    val metricsCountKey = MetricType("$metricsKeyName Call Count", MetricUnit.Count)
    override suspend fun find(
        condition: Condition<M>,
        orderBy: List<SortPart<M>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long
    ): Flow<M> = Metrics.addPerformanceToSumPerHandler(metricsKey, metricsCountKey) {
        val source = wraps.find(condition, orderBy, skip, limit, maxQueryMs)
        flow {
            var now = System.nanoTime()
            var timeSum = 0L
            Metrics.addToSumPerHandler(metricsCountKey, 1.0)
            try {
                source.collect {
                    timeSum += (System.nanoTime() - now)
                    emit(it)
                    now = System.nanoTime()
                }
            } finally {
                Metrics.addToSumPerHandler(metricsKey, timeSum / 1000000.0)
            }
        }
    }

    override suspend fun count(condition: Condition<M>): Int = Metrics.addPerformanceToSumPerHandler(metricsKey, metricsCountKey) { wraps.count(condition) }

    override suspend fun <Key> groupCount(condition: Condition<M>, groupBy: DataClassPath<M, Key>): Map<Key, Int> = Metrics.addPerformanceToSumPerHandler(metricsKey, metricsCountKey) {
        wraps.groupCount(condition, groupBy)
    }

    override suspend fun <N : Number?> aggregate(
        aggregate: Aggregate,
        condition: Condition<M>,
        property: DataClassPath<M, N>
    ): Double? = Metrics.addPerformanceToSumPerHandler(metricsKey, metricsCountKey) {
        wraps.aggregate(aggregate, condition, property)
    }

    override suspend fun <N : Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<M>,
        groupBy: DataClassPath<M, Key>,
        property: DataClassPath<M, N>
    ): Map<Key, Double?> = Metrics.addPerformanceToSumPerHandler(metricsKey, metricsCountKey) {
        wraps.groupAggregate(aggregate, condition, groupBy, property)
    }

    override suspend fun insert(models: Iterable<M>): List<M> = Metrics.addPerformanceToSumPerHandler(metricsKey, metricsCountKey) {
        wraps.insert(models)
    }

    override suspend fun replaceOne(condition: Condition<M>, model: M, orderBy: List<SortPart<M>>): EntryChange<M> = Metrics.addPerformanceToSumPerHandler(metricsKey, metricsCountKey) {
        wraps.replaceOne(condition, model, orderBy)
    }

    override suspend fun replaceOneIgnoringResult(
        condition: Condition<M>,
        model: M,
        orderBy: List<SortPart<M>>
    ): Boolean = Metrics.addPerformanceToSumPerHandler(metricsKey, metricsCountKey) {
        wraps.replaceOneIgnoringResult(condition, model, orderBy)
    }

    override suspend fun upsertOne(condition: Condition<M>, modification: Modification<M>, model: M): EntryChange<M> = Metrics.addPerformanceToSumPerHandler(metricsKey, metricsCountKey) {
        wraps.upsertOne(condition, modification, model)
    }

    override suspend fun upsertOneIgnoringResult(
        condition: Condition<M>,
        modification: Modification<M>,
        model: M
    ): Boolean = Metrics.addPerformanceToSumPerHandler(metricsKey, metricsCountKey) {
        wraps.upsertOneIgnoringResult(condition, modification, model)
    }

    override suspend fun updateOne(
        condition: Condition<M>,
        modification: Modification<M>,
        orderBy: List<SortPart<M>>
    ): EntryChange<M> = Metrics.addPerformanceToSumPerHandler(metricsKey, metricsCountKey) {
        wraps.updateOne(condition, modification, orderBy)
    }

    override suspend fun updateOneIgnoringResult(
        condition: Condition<M>,
        modification: Modification<M>,
        orderBy: List<SortPart<M>>
    ): Boolean = Metrics.addPerformanceToSumPerHandler(metricsKey, metricsCountKey) {
        wraps.updateOneIgnoringResult(condition, modification, orderBy)
    }

    override suspend fun updateMany(condition: Condition<M>, modification: Modification<M>): CollectionChanges<M> = Metrics.addPerformanceToSumPerHandler(metricsKey, metricsCountKey) {
        wraps.updateMany(condition, modification)
    }

    override suspend fun updateManyIgnoringResult(condition: Condition<M>, modification: Modification<M>): Int = Metrics.addPerformanceToSumPerHandler(metricsKey, metricsCountKey) {
        wraps.updateManyIgnoringResult(condition, modification)
    }

    override suspend fun deleteOne(condition: Condition<M>, orderBy: List<SortPart<M>>): M? = Metrics.addPerformanceToSumPerHandler(metricsKey, metricsCountKey) {
        wraps.deleteOne(condition, orderBy)
    }

    override suspend fun deleteOneIgnoringOld(condition: Condition<M>, orderBy: List<SortPart<M>>): Boolean = Metrics.addPerformanceToSumPerHandler(metricsKey, metricsCountKey) {
        wraps.deleteOneIgnoringOld(condition, orderBy)
    }

    override suspend fun deleteMany(condition: Condition<M>): List<M> = Metrics.addPerformanceToSumPerHandler(metricsKey, metricsCountKey) {
        wraps.deleteMany(condition)
    }

    override suspend fun deleteManyIgnoringOld(condition: Condition<M>): Int = Metrics.addPerformanceToSumPerHandler(metricsKey, metricsCountKey) {
        wraps.deleteManyIgnoringOld(condition)
    }
}

fun <Model : Any> FieldCollection<Model>.metrics(metricsKeyName: String): FieldCollection<Model> =
    MetricsFieldCollection(this, metricsKeyName)
