package com.lightningkite.lightningdb

import kotlinx.serialization.Serializable

@Serializable
data class CollectionUpdates<T: HasId<ID>, ID: Comparable<ID>>(
    val updates: Set<T> = setOf(),
    val remove: Set<ID> = setOf(),
    val overload: Boolean = false
)