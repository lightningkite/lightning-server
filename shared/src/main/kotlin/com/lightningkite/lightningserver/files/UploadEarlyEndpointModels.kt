@file:SharedCode
@file:UseContextualSerialization(UUID::class, ServerFile::class, Instant::class)

package com.lightningkite.lightningserver.files

import com.lightningkite.khrysalis.SharedCode
import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.ServerFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.time.Duration
import java.time.Instant
import java.util.*


@GenerateDataClassPaths
@Serializable
data class UploadForNextRequest(
    override val _id: UUID = UUID.randomUUID(),
    val file: ServerFile,
    val expires: Instant = Instant.now().plus(Duration.ofMinutes(15))
) : HasId<UUID>

@Serializable
data class UploadInformation(
    val uploadUrl: String,
    val futureCallToken: String
)