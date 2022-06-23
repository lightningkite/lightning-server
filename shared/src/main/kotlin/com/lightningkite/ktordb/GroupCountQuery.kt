package com.lightningkite.ktordb

import kotlinx.serialization.Serializable

@Serializable
data class GroupCountQuery<Model>(
    val condition: Condition<Model> = Condition.Always(),
    val groupBy: KProperty1Partial<Model>
)

@Serializable
data class AggregateQuery<Model>(
    val aggregate: Aggregate,
    val condition: Condition<Model> = Condition.Always(),
    val property: KProperty1Partial<Model>
)

@Serializable
data class GroupAggregateQuery<Model>(
    val aggregate: Aggregate,
    val condition: Condition<Model> = Condition.Always(),
    val groupBy: KProperty1Partial<Model>,
    val property: KProperty1Partial<Model>
)
