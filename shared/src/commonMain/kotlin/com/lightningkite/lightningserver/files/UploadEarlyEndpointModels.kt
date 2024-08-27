
@file:UseContextualSerialization(UUID::class, ServerFile::class, Instant::class)

package com.lightningkite.lightningserver.files

import com.lightningkite.UUID

import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasId
import com.lightningkite.uuid
import com.lightningkite.now
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.datetime.Instant

import kotlin.time.Duration.Companion.minutes


@GenerateDataClassPaths
@Serializable
data class UploadForNextRequest(
    override val _id: UUID = uuid(),
    val file: ServerFile,
    val expires: Instant = now().plus(15.minutes)
) : HasId<UUID>

@Serializable
data class UploadInformation(
    val uploadUrl: String,
    val futureCallToken: String
)