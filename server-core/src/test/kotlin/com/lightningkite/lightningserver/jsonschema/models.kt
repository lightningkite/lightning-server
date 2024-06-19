@file:UseContextualSerialization(UUID::class)

package com.lightningkite.lightningserver.jsonschema

import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasId
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.util.*
import com.lightningkite.UUID
import com.lightningkite.uuid

@Serializable
@GenerateDataClassPaths
data class Post(
    override val _id: UUID = uuid(),
    val author: String,
    val title: String,
    val content: String
) : HasId<UUID>
