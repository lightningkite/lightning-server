package com.lightningkite.ktordb

import com.lightningkite.khrysalis.IsHashable
import kotlin.math.sqrt

enum class Aggregate {
    Sum,
    Average,
    StandardDeviationSample,
    StandardDeviationPopulation
}

fun Aggregate.aggregator(): Aggregator = when(this) {
    Aggregate.Sum -> SumAggregator()
    Aggregate.Average -> AverageAggregator()
    Aggregate.StandardDeviationSample -> StandardDeviationSampleAggregator()
    Aggregate.StandardDeviationPopulation -> StandardDeviationPopulationAggregator()
}

interface Aggregator {
    fun consume(value: Double)
    fun complete(): Double
}

class SumAggregator: Aggregator {
    var current: Double = 0.0
    override fun consume(value: Double) {
        current += value
    }
    override fun complete(): Double = current
}
class AverageAggregator: Aggregator {
    var count: Int = 0
    var current: Double = 0.0
    override fun consume(value: Double) {
        count++
        current += (value - current) / count
    }
    override fun complete(): Double = current
}
class StandardDeviationSampleAggregator: Aggregator {
    var count: Int = 0
    var mean: Double = 0.0
    var m2: Double = 0.0
    override fun consume(value: Double) {
        count++
        val delta1 = value - mean
        mean += (delta1) / count
        val delta2 = value - mean
        m2 += delta1 * delta2
    }
    override fun complete(): Double = if(count < 2) 0.0 else sqrt(m2 / (count - 1))
}
class StandardDeviationPopulationAggregator: Aggregator {
    var count: Int = 0
    var mean: Double = 0.0
    var m2: Double = 0.0
    override fun consume(value: Double) {
        count++
        val delta1 = value - mean
        mean += (delta1) / count
        val delta2 = value - mean
        m2 += delta1 * delta2
    }
    override fun complete(): Double = if(count == 0) 0.0 else sqrt(m2 / count)
}

fun Sequence<Double>.aggregate(aggregate: Aggregate): Double {
    val aggregator = aggregate.aggregator()
    for(item in this) {
        aggregator.consume(item)
    }
    return aggregator.complete()
}

fun <GROUP: IsHashable> Sequence<Pair<GROUP, Double>>.aggregate(aggregate: Aggregate): Map<GROUP, Double> {
    val aggregators = HashMap<GROUP, Aggregator>()
    for(entry in this) {
        aggregators.getOrPut(entry.first) { aggregate.aggregator() }.consume(entry.second)
    }
    return aggregators.mapValues { it.value.complete() }
}