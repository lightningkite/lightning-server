@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningserver.db.testmodels

import com.lightningkite.lightningdb.DatabaseModel
import com.lightningkite.lightningdb.HasId
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.util.*


@DatabaseModel
@Serializable
data class TestThing(override val _id: UUID = UUID.randomUUID(), val value: Int = 0) : HasId<UUID>

@DatabaseModel
@Serializable
data class TempThing(override val _id: Int): HasId<Int>
