@file:SharedCode
@file:UseContextualSerialization(UUID::class, ServerFile::class, Instant::class)

package com.lightningkite.lightningserver.files

import com.lightningkite.UUID
import com.lightningkite.khrysalis.SharedCode
import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.ServerFile
import com.lightningkite.uuid
import kotlinx.datetime.Clock
import com.lightningkite.now
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlin.time.Duration
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