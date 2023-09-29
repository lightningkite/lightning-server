@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningserver.testmodels

import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasEmail
import com.lightningkite.lightningdb.HasId
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import com.lightningkite.UUID
import com.lightningkite.uuid
import java.util.*


@GenerateDataClassPaths
@Serializable
data class TestUser(
    override val _id: UUID = uuid(),
    override val email: String,
    val isSuperAdmin: Boolean = false,
) : HasId<UUID>, HasEmail