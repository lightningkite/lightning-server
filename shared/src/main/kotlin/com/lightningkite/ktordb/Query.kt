@file:SharedCode
package com.lightningkite.ktordb

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
        makeCondition: (PropChain<T, T>)->Condition<T>,
        orderBy: List<SortPart<T>>,
        skip: Int = 0,
        limit: Int = 100,
    ):this(makeCondition(startChain()), orderBy, skip, limit)
}