package com.lightningkite.lightningserver.media

import com.lightningkite.MediaType
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.HasId
import com.lightningkite.services.files.ServerFile
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable
@GenerateDataClassPaths
public data class ServerFileWithMetadata(
    val original: ServerFile,
    val mimeType: MediaType? = null,
    val size: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val previews: List<ServerFileWithMetadataPreview> = listOf()
) {

    public fun previews(
        supportedTypes: Set<MediaType>,
        preferredMinimumWidth: Int? = null,
        preferredMinimumHeight: Int? = null,
    ): Sequence<ServerFileWithMetadataPreview> = previews
        .asSequence()
        .filter { it.mimeType in supportedTypes }
        .sortedBy {
            val diffWidth = preferredMinimumWidth?.let { d ->
                it.width?.let { a ->
                    if (a >= d) a - d else 2000 + d - a
                }
            } ?: 0
            val diffHeight = preferredMinimumHeight?.let { d ->
                it.height?.let { a ->
                    if (a >= d) a - d else 2000 + d - a
                }
            } ?: 0
            diffWidth + diffHeight
        }
}

@Serializable
@GenerateDataClassPaths
public data class ServerFileWithMetadataPreview(
    val file: ServerFile,
    val mimeType:MediaType,
    val size: Long,
    val width: Int? = null,
    val height: Int? = null
)