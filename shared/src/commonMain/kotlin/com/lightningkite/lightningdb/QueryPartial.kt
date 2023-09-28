package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.IsCodableAndHashable
import kotlinx.serialization.Serializable

@Serializable
data class QueryPartial<T: IsCodableAndHashable>(
    val fields: Set<DataClassPathPartial<T>>,
    val condition: Condition<T> = Condition.Always<T>(),
    val orderBy: List<SortPart<T>> = listOf(),
    val skip: Int = 0,
    val limit: Int = 100,
)