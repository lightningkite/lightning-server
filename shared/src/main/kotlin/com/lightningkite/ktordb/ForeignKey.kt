package com.lightningkite.ktordb

@kotlinx.serialization.Serializable
data class ForeignKey<Model: HasId<ID>, ID: Comparable<ID>>(val id: ID)
