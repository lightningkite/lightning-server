package com.lightningkite.lightningserver.files

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.HasId
import com.lightningkite.services.files.ServerFile
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Record for tracking a prepared upload and guarding against reuse.
 * Stored in the database to enable cleanup and single-use semantics.
 */
@GenerateDataClassPaths
@Serializable
public data class UploadForNextRequest(
    override val _id: Uuid = Uuid.random(),
    /** The file location (as a ServerFile URL) associated with this prepared upload. */
    val file: ServerFile,
    /** Expiration time after which the upload is considered invalid/garbage. */
    val expires: Instant,
) : HasId<Uuid>

/**
 * Information returned to a client when initiating an early upload.
 *
 * - [uploadUrl]: Presigned PUT URL to upload bytes to.
 * - [futureCallToken]: A token that can be serialized/deserialized as a ServerFile in a later request.
 *   Typically time-limited; clients should use it promptly.
 */
@Serializable
public data class UploadInformation(
    val uploadUrl: String,
    val futureCallToken: String,
)

/*
TODO(API): Consider a stronger type than raw String for futureCallToken to avoid confusion and accidental misuse.
*/
