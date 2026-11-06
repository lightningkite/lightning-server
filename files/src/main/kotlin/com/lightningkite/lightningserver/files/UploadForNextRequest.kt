package com.lightningkite.lightningserver.files

import com.lightningkite.services.data.Queryable
import com.lightningkite.services.database.HasId
import com.lightningkite.services.files.ServerFile
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Record for tracking a prepared upload and guarding against reuse.
 * Stored in the database to enable cleanup and single-use semantics.
 *
 * **Do not expose this table through a REST or model endpoint.** Between preparation and verification
 * [file] names a file in the upload jail, which has not been scanned. Serializing one hands a client a
 * signed URL that [com.lightningkite.services.files.ExternalServerFileSerializer] will accept straight
 * back as a usable `ServerFile`, bypassing scanning entirely.
 */
@Queryable
public data class UploadForNextRequest(
    override val _id: Uuid = Uuid.random(),
    /** The file location (as a ServerFile URL) associated with this prepared upload. */
    val file: ServerFile,
    /** Expiration time after which the upload is considered invalid/garbage. */
    val expires: Instant,
) : HasId<Uuid>
