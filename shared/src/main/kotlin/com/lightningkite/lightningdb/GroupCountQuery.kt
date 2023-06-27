@file:SharedCode
package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.IsCodableAndHashable
import com.lightningkite.khrysalis.SharedCode
import kotlinx.serialization.Serializable

@Serializable
data class GroupCountQuery<Model: IsCodableAndHashable>(
    val condition: Condition<Model> = Condition.Always(),
    val groupBy: DataClassPathPartial<Model>
)

@Serializable
data class AggregateQuery<Model: IsCodableAndHashable>(
    val aggregate: Aggregate,
    val condition: Condition<Model> = Condition.Always(),
    val property: DataClassPathPartial<Model>
)

@Serializable
data class GroupAggregateQuery<Model: IsCodableAndHashable>(
    val aggregate: Aggregate,
    val condition: Condition<Model> = Condition.Always(),
    val groupBy: DataClassPathPartial<Model>,
    val property: DataClassPathPartial<Model>
)
