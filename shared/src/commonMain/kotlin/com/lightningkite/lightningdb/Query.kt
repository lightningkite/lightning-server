package com.lightningkite.lightningdb

import com.lightningkite.serialization.DataClassPath
import kotlinx.serialization.Serializable

@Serializable
data class Query<T >(
    val condition: Condition<T> = Condition.Always<T>(),
    val orderBy: List<SortPart<T>> = listOf(),
    val skip: Int = 0,
    val limit: Int = 100,
) {
}


inline fun <reified T > Query(
    orderBy: List<SortPart<T>> = listOf(),
    skip: Int = 0,
    limit: Int = 100,
    makeCondition: (DataClassPath<T, T>) -> Condition<T>,
): Query<T> = Query(makeCondition(path()), orderBy, skip, limit)