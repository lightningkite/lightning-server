package com.lightningkite.lightningserver.files

import com.lightningkite.services.files.ServerFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Where to upload a new file, returned by `getFileUploadUrl`.
 *
 * PUT the file's bytes to [uploadUrl], then pass [token] to `verifyUpload`.
 */
@Serializable
public data class UploadTarget(
    val uploadUrl: String,
    // Claude: keeps the wire format of UploadInformation, so old clients and other SDKs decode it unchanged
    @SerialName("futureCallToken") val token: UnverifiedUploadToken,
)

/**
 * A file the client has been told to upload, which a later request can't use yet.
 *
 * Pass it to `verifyUpload` to get a [ServerFile] that requests can use.
 */
@Serializable
@JvmInline
public value class UnverifiedUploadToken(public val raw: String)

/**
 * Information returned to a client when initiating an early upload.
 *
 * - [uploadUrl]: Presigned PUT URL to upload bytes to.
 * - [futureCallToken]: A token that can be serialized/deserialized as a ServerFile in a later request.
 *   Typically time-limited; clients should use it promptly.
 */
@Deprecated("Use UploadTarget, which types its token.", ReplaceWith("UploadTarget"))
@Serializable
public data class UploadInformation(
    val uploadUrl: String,
    val futureCallToken: String,
)
