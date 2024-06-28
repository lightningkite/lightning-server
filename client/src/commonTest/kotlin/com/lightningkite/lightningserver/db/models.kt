package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasId
import kotlinx.serialization.Serializable


@GenerateDataClassPaths
@Serializable
data class Item(override val _id: Int, val creation: Int = 0) : HasId<Int>