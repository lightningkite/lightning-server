package com.lightningkite.lightningserver.files

import com.lightningkite.MimeType
import kotlinx.datetime.Instant

/**
 * Holds common information about files.
 */
data class FileInfo(
    val type: MimeType,
    val size: Long,
    val lastModified: Instant
)
