package com.lightningkite.lightningserver.db

import kotlinx.serialization.Serializable

@Serializable
data class ListChange<T>(
    val wholeList: List<T>? = null,
    val old: T? = null,
    val new: T? = null
)

