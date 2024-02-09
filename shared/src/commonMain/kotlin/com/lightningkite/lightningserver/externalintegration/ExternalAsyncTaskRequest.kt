@file:UseContextualSerialization(Instant::class)

package com.lightningkite.lightningserver.externalintegration

import com.lightningkite.lightningdb.AdminTableColumns
import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.Index
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.datetime.Instant

@GenerateDataClassPaths
@Serializable
@AdminTableColumns(["_id", "action", "createdAt", "result", "processingError"])
data class ExternalAsyncTaskRequest(
    override val _id: String,
    @Index val ourData: String,
    val expiresAt: Instant,
    val createdAt: Instant = Clock.System.now(),
    val response: String? = null,
    val result: String? = null,
    val action: String? = null,
    val lastAttempt: Instant = Instant.DISTANT_PAST,
    val processingError: String? = null,
) : HasId<String>

