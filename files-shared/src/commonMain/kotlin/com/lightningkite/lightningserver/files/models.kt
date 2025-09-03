package com.lightningkite.lightningserver.files

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.HasId
import com.lightningkite.services.files.ServerFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid


@GenerateDataClassPaths
@Serializable
public data class UploadForNextRequest(
    override val _id: Uuid = Uuid.random(),
    val file: ServerFile,
    val expires: Instant
) : HasId<Uuid>

@Serializable
public data class UploadInformation(
    val uploadUrl: String,
    val futureCallToken: String
)
