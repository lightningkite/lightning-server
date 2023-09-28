package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.core.ContentType
import kotlinx.datetime.Instant

/**
 * Holds common information about files.
 */
data class FileInfo(
    val type: ContentType,
    val size: Long,
    val lastModified: Instant
)
