package com.lightningkite.lightningdb

import kotlinx.serialization.Serializable

@Serializable
data class Mask<T>(
    val pairs: List<Pair<Condition<T>, Modification<T>>> = listOf()
) {
    operator fun invoke(on: T): T {
        var value = on
        for(pair in pairs) {
            if(pair.first(on)) value = pair.second(on)
        }
        return value
    }
}
