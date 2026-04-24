package com.lightningkite.lightningserver.media

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.files.ServerFile
import kotlinx.serialization.Serializable

/**
 * Represents a server file with associated metadata and optional preview variants.
 *
 * This data class wraps a [ServerFile] with additional metadata about the file's properties
 * (such as dimensions for images) and maintains a list of preview variants that have been
 * generated (e.g., thumbnails, different formats, or scaled versions).
 *
 * @property original The original server file reference
 * @property mimeType The MIME type of the file, if known
 * @property size The file size in bytes, if known
 * @property width The width in pixels for image files, if applicable
 * @property height The height in pixels for image files, if applicable
 * @property previews List of generated preview variants
 */
@Serializable
@GenerateDataClassPaths
public data class ServerFileWithMetadata(
    val original: ServerFile,
    val mimeType: MediaType? = null,
    val size: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val previews: List<ServerFileWithMetadataPreview> = listOf(),
) {

    /**
     * Filters and sorts previews based on supported types and preferred dimensions.
     *
     * Returns a sequence of previews that match the specified supported types, sorted by
     * how closely they match the preferred dimensions. Previews that meet or exceed the
     * preferred dimensions are prioritized, with smaller "overage" ranked higher.
     *
     * **Note:** The sorting algorithm adds a penalty of 2000 pixels for previews that are
     * smaller than the preferred dimensions, effectively deprioritizing undersized previews.
     *
     * @param supportedTypes Set of MIME types that are acceptable
     * @param preferredMinimumWidth Desired minimum width in pixels, or null for any width
     * @param preferredMinimumHeight Desired minimum height in pixels, or null for any height
     * @return Sequence of matching previews, sorted by best fit
     */
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

/**
 * Represents a preview variant of a media file.
 *
 * Preview variants are typically generated versions of the original file in different
 * formats, sizes, or quality levels (e.g., thumbnails, web-optimized images).
 *
 * @property file The server file reference for this preview
 * @property mimeType The MIME type of the preview file
 * @property size The file size in bytes
 * @property width The width in pixels for image previews, if applicable
 * @property height The height in pixels for image previews, if applicable
 */
@Serializable
@GenerateDataClassPaths
public data class ServerFileWithMetadataPreview(
    val file: ServerFile,
    val mimeType: MediaType,
    val size: Long,
    val width: Int? = null,
    val height: Int? = null,
)

/*
 * TODO: API Recommendations
 *
 * 1. Consider adding a `findBestPreview()` method that returns a single preview (or the original)
 *    rather than a sequence, as this is likely a common use case.
 *
 * 2. The sorting penalty value (2000) in the `previews()` method is a magic number. Consider:
 *    - Making it a configurable parameter with a sensible default
 *    - Or documenting why 2000 was chosen
 *
 * 3. Consider adding a `totalSize` property to ServerFileWithMetadata that includes the sum of
 *    all preview sizes, useful for storage management.
 *
 * 4. The `previews()` method could benefit from accepting a lambda for custom sorting logic,
 *    allowing callers to define their own "best match" criteria.
 *
 * 5. Consider adding validation that prevents width/height from being set on non-image files,
 *    or at minimum document the expected behavior.
 */