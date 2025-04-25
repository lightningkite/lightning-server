@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningserver.testmodels

import com.lightningkite.lightningserver.db.GenerateDataClassPaths
import com.lightningkite.lightningserver.db.HasEmail
import com.lightningkite.lightningserver.db.HasId
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import com.lightningkite.UUID
import com.lightningkite.uuid


@GenerateDataClassPaths
@Serializable
data class TestUser(
    override val _id: UUID = UUID.random(),
    override val email: String,
    val phoneNumber: String? = null,
    val isSuperAdmin: Boolean = false,
) : HasId<UUID>, HasEmail