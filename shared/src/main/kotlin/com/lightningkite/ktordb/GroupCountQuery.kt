package com.lightningkite.ktordb

import kotlinx.serialization.Serializable

@Serializable
data class GroupCountQuery<Model>(
    val condition: Condition<Model> = Condition.Always(),
    val groupBy: PartialDataClassProperty<Model>
)

@Serializable
data class AggregateQuery<Model>(
    val aggregate: Aggregate,
    val condition: Condition<Model> = Condition.Always(),
    val property: PartialDataClassProperty<Model>
)

@Serializable
data class GroupAggregateQuery<Model>(
    val aggregate: Aggregate,
    val condition: Condition<Model> = Condition.Always(),
    val groupBy: PartialDataClassProperty<Model>,
    val property: PartialDataClassProperty<Model>
)
