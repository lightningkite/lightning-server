package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.IsHashable


/**
 * Runs an aggregation directly on the system.
 * Used for testing and aggregating in the RAM test database.
 */
fun <GROUP : IsHashable> Sequence<Pair<GROUP, Double>>.aggregate(aggregate: Aggregate): Map<GROUP, Double?> {
    val aggregators = HashMap<GROUP, Aggregator>()
    for (entry in this) {
        aggregators.getOrPut(entry.first) { aggregate.aggregator() }.consume(entry.second)
    }
    return aggregators.mapValues { it.value.complete() }
}