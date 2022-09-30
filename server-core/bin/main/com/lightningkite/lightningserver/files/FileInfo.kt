package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.core.ContentType
import java.time.Instant

data class FileInfo(
    val type: ContentType,
    val size: Long,
    val lastModified: Instant
)
