@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningserver.testmodels

import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasEmail
import com.lightningkite.lightningdb.HasId
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.util.*


@GenerateDataClassPaths
@Serializable
data class TestUser(override val _id: UUID = UUID.randomUUID(), override val email: String) : HasId<UUID>, HasEmail