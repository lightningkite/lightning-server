@file:UseContextualSerialization(ServerFile::class)
package com.lightningkite.lightningserver.media

import com.lightningkite.lightningserver.files.*
import com.lightningkite.lightningdb.GenerateDataClassPaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlin.math.absoluteValue

@Serializable
@GenerateDataClassPaths
data class ServerFileWithMetadata(
    val original: ServerFile,
    val mimeType: String? = null,
    val size: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val previews: List<ServerFileWithMetadataPreview> = listOf()
) {
    fun get(
        supportedTypes: Set<String>,
        greaterThanWidth: Int? = null,
        greaterThanHeight: Int? = null,
    ) = previews
        .asSequence()
        .filter { it.mimeType in supportedTypes }
        .sortedBy {
            val diffWidth = greaterThanWidth?.let { d ->
                it.width?.let { a ->
                    if(a >= d) a - d else 2000 + d - a
                }
            } ?: 0
            val diffHeight = greaterThanHeight?.let { d ->
                it.height?.let { a ->
                    if(a >= d) a - d else 2000 + d - a
                }
            } ?: 0
            diffWidth + diffHeight
        }
}

@Serializable
@GenerateDataClassPaths
data class ServerFileWithMetadataPreview(
    val file: ServerFile,
    val mimeType: String,
    val size: Long,
    val width: Int? = null,
    val height: Int? = null
)