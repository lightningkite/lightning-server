package com.lightningkite.ktordb

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import com.lightningkite.khrysalis.IsCodableAndHashableNotNull

data class QuerySet<Model: Any>(
    val on: FieldCollection<Model>,
    val condition: Condition<Model>,
): Flow<Model> {
    override suspend fun collect(collector: FlowCollector<Model>) {
        on.find(condition).collect(collector)
    }
}

data class SortedQuerySet<Model: Any>(
    val on: FieldCollection<Model>,
    val condition: Condition<Model>,
    val orderBy: List<SortPart<Model>>
): Flow<Model> {
    override suspend fun collect(collector: FlowCollector<Model>) {
        on.find(condition, orderBy).collect(collector)
    }
}

data class SlicedSortedQuerySet<Model: Any>(
    val on: FieldCollection<Model>,
    val condition: Condition<Model>,
    val orderBy: List<SortPart<Model>>,
    val skip: Int,
    val limit: Int
): Flow<Model> {
    override suspend fun collect(collector: FlowCollector<Model>) {
        on.find(condition, orderBy, skip, limit).collect(collector)
    }
}

fun <Model: Any> FieldCollection<Model>.filter(conditionMaker: (PropChain<Model, Model>) -> Condition<Model>) = QuerySet(this, conditionMaker(
    startChain()
))

@Suppress("UNCHECKED_CAST")
private fun <Model, V: Comparable<V>> PropChain<Model, V>.field(): DataClassProperty<Model, V> = (mapCondition(Condition.Always()) as Condition.OnField<Model, V>).key as DataClassProperty<Model, V>

fun <Model: Any> QuerySet<Model>.filter(conditionMaker: (PropChain<Model, Model>) -> Condition<Model>) = copy(on, condition and conditionMaker(startChain()))
fun <Model: Any> SortedQuerySet<Model>.filter(conditionMaker: (PropChain<Model, Model>) -> Condition<Model>) = copy(on, condition and conditionMaker(startChain()))
fun <Model: Any, V: Comparable<V>> QuerySet<Model>.sortedBy(field: DataClassProperty<Model, V>) = SortedQuerySet(on, condition, orderBy = listOf(SortPart(field)))
fun <Model: IsCodableAndHashableNotNull, V: Comparable<V>> QuerySet<Model>.sortedBy(field: (PropChain<Model, Model>) -> PropChain<Model, V>) = SortedQuerySet(on, condition, orderBy = listOf(SortPart(field(startChain()).field())))
fun <Model: Any, V: Comparable<V>> QuerySet<Model>.sortedByDescending(field: DataClassProperty<Model, V>) = SortedQuerySet(on, condition, orderBy = listOf(SortPart(field, ascending = false)))
fun <Model: IsCodableAndHashableNotNull, V: Comparable<V>> QuerySet<Model>.sortedByDescending(field: (PropChain<Model, Model>) -> PropChain<Model, V>) = SortedQuerySet(on, condition, orderBy = listOf(SortPart((field(startChain()).field()), ascending = false)))
fun <Model: Any> SortedQuerySet<Model>.take(count: Int) = SlicedSortedQuerySet(on, condition, orderBy, skip = 0, limit = count)
fun <Model: Any> SortedQuerySet<Model>.drop(count: Int) = SlicedSortedQuerySet(on, condition, orderBy, skip = count, limit = Int.MAX_VALUE)
fun <Model: Any> SlicedSortedQuerySet<Model>.take(count: Int) = copy(limit = limit + count)
fun <Model: Any> SlicedSortedQuerySet<Model>.drop(count: Int) = copy(skip = skip + count)
