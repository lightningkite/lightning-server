@file:UseContextualSerialization(Instant::class)

package com.lightningkite.lightningserver.externalintegration

import com.lightningkite.lightningdb.DatabaseModel
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.Index
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.time.Instant

@DatabaseModel
@Serializable
data class ExternalAsyncTaskRequest(
    override val _id: String,
    @Index val ourData: String,
    val expiresAt: Instant,
    val createdAt: Instant = Instant.now(),
    val response: String? = null,
    val result: String? = null,
    val action: String? = null,
    val lastAttempt: Instant = Instant.EPOCH,
    val processingError: String? = null,
) : HasId<String>

