
package com.lightningkite.lightningdb

import com.lightningkite.serialization.DataClassPathPartial
import kotlinx.serialization.Serializable

@Serializable
data class GroupCountQuery<Model>(
    val condition: Condition<Model> = Condition.Always,
    val groupBy: DataClassPathPartial<Model>
)

@Serializable
data class AggregateQuery<Model>(
    val aggregate: Aggregate,
    val condition: Condition<Model> = Condition.Always,
    val property: DataClassPathPartial<Model>
)

@Serializable
data class GroupAggregateQuery<Model>(
    val aggregate: Aggregate,
    val condition: Condition<Model> = Condition.Always,
    val groupBy: DataClassPathPartial<Model>,
    val property: DataClassPathPartial<Model>
)
