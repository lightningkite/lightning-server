@file:SharedCode
package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.IsCodableAndHashable
import com.lightningkite.khrysalis.SharedCode
import kotlinx.serialization.Serializable

@Serializable
data class Query<T: IsCodableAndHashable>(
    val condition: Condition<T> = Condition.Always<T>(),
    val orderBy: List<SortPart<T>> = listOf(),
    val skip: Int = 0,
    val limit: Int = 100,
) {
    constructor(
        orderBy: List<SortPart<T>> = listOf(),
        skip: Int = 0,
        limit: Int = 100,
        makeCondition: (PropChain<T, T>)->Condition<T>,
    ):this(makeCondition(startChain()), orderBy, skip, limit)
}