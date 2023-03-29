package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.IsHashable


fun <GROUP : IsHashable> Sequence<Pair<GROUP, Double>>.aggregate(aggregate: Aggregate): Map<GROUP, Double?> {
    val aggregators = HashMap<GROUP, Aggregator>()
    for (entry in this) {
        aggregators.getOrPut(entry.first) { aggregate.aggregator() }.consume(entry.second)
    }
    return aggregators.mapValues { it.value.complete() }
}