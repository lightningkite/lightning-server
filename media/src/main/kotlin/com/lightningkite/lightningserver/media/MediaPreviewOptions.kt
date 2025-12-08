package com.lightningkite.lightningserver.media

import com.lightningkite.MediaType
import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.metadata.Orientation
import com.sksamuel.scrimage.nio.BmpWriter
import com.sksamuel.scrimage.nio.GifWriter
import com.sksamuel.scrimage.nio.JpegWriter
import com.sksamuel.scrimage.nio.PngWriter
import com.sksamuel.scrimage.nio.TiffWriter
import com.sksamuel.scrimage.webp.WebpWriter
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import kotlin.jvm.optionals.getOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Configuration options for generating media previews (thumbnails, resized images, format conversions).
 *
 * This class defines parameters for transforming images, including resizing, aspect ratio adjustment,
 * format conversion, and quality settings. All parameters are optional; when all are null, the preview
 * will only apply EXIF orientation corrections without other transformations.
 *
 * @property sizeInPixels The maximum size allowed for both width and height in pixels. Images larger
 *                        than this in either dimension will be scaled down proportionally.
 * @property forceRatio The aspect ratio to enforce (width/height). If set, the image will be cropped
 *                      or padded to match this ratio. For example, 16.0/9.0 for widescreen format.
 * @property type The target media type for format conversion. If null, the original format is preserved.
 * @property quality The quality level for lossy compression (0.0 to 1.0). Primarily used for JPEG encoding.
 *                   Defaults to 0.95 (95%) if not specified for JPEG output.
 * @property destination The output file location. If null, a temporary file will be created.
 */
public data class MediaPreviewOptions(
    val sizeInPixels: Int? = null,
    val forceRatio: Double? = null,
    val type: MediaType? = null,
    val quality: Double? = null,
    val destination: File? = null,
) {
    init {
        require(sizeInPixels == null || sizeInPixels > 0) { "sizeInPixels must be positive" }
        require(forceRatio == null || forceRatio in 0.00001..10000.0) { "forceRatio must be positive" }
        require(quality == null || quality in 0.0..1.0) { "quality must be between 0.0 and 1.0" }
    }
    public companion object {
        /**
         * A preview option that only corrects EXIF orientation and other image metadata issues
         * without performing any resizing, format conversion, or quality adjustments.
         */
        public val CorrectOddFeatures: MediaPreviewOptions = MediaPreviewOptions()
    }

    /**
     * Generates a string representation suitable for use in filenames.
     *
     * Creates a descriptive string containing the configured options (e.g., "800-ratio1.77-jpg-quality80").
     * Returns "corrected" when no transformations are configured.
     */
    override fun toString(): String = listOfNotNull(
        sizeInPixels?.toString(),
        forceRatio?.let { "ratio${forceRatio}" },
        type?.extension,
        quality?.let { "quality${it.times(100).roundToInt()}" },
    ).let { if (it.isEmpty()) "corrected" else it.joinToString("-") }
}

/**
 * Contains information about a processed media file.
 *
 * This data class represents the result of applying preview options to an image,
 * including the output file location and its properties.
 *
 * @property file The file containing the processed image
 * @property mimeType The MIME type of the processed file
 * @property size The file size in bytes
 * @property width The width of the processed image in pixels
 * @property height The height of the processed image in pixels
 */
public data class FileMediaInfo(
    val file: File,
    val mimeType: MediaType,
    val size: Long,
    val width: Int,
    val height: Int,
)

/**
 * Applies the specified preview options to this image and writes the result to a file.
 *
 * This function processes the image according to the provided options, which may include
 * resizing, aspect ratio adjustment, format conversion, and quality settings. If no processing
 * is needed (e.g., the image already meets the criteria), this function returns null.
 *
 * **Processing order:**
 * 1. Aspect ratio adjustment (if [MediaPreviewOptions.forceRatio] is set)
 * 2. Scaling (if [MediaPreviewOptions.sizeInPixels] is set)
 * 3. Format conversion and quality adjustment
 *
 * **Optimization:** Returns null when:
 * - No resizing is needed
 * - No aspect ratio change is needed
 * - No format conversion is requested
 * - For JPEG images, EXIF orientation is already correct (Zero or null)
 *
 * **Supported formats:** PNG, JPEG, WebP, TIFF, GIF, BMP
 * **Unsupported formats:** APNG, AVIF (will log warning and return null)
 *
 * @param options The preview generation options
 * @param originalType The MIME type of the original image
 * @return File information about the processed image, or null if no processing was needed
 */
public fun ImmutableImage.apply(
    options: MediaPreviewOptions,
    originalType: MediaType,
): FileMediaInfo? {
    val needsScaling = options.sizeInPixels != null && (options.sizeInPixels < width || options.sizeInPixels < height)
    val needsRatio = options.forceRatio != null && abs(width.toDouble() / height - options.forceRatio) > 0.01
    val canSkip = !needsScaling &&
            !needsRatio &&
            (options.type == null || originalType == options.type) &&
            ((options.type ?: originalType) != MediaType.Image.JPEG || this.metadata.orientation?.getOrNull().let { it == Orientation.Zero || it == null })

    if (canSkip) return null

    val basis = this
    var processing = basis
    val destinationFile = options.destination ?: File.createTempFile("resized", originalType.extension)

    if (needsRatio)
        processing = processing.resizeToRatio(options.forceRatio)

    if (needsScaling) {
        // After ratio adjustment (if any), check if scaling is still needed
        // Use processing dimensions (after ratio adjustment) for scale factor calculation
        val scaleFactor =
            kotlin.math.max(
                options.sizeInPixels / processing.width.toDouble(),
                options.sizeInPixels / processing.height.toDouble()
            ).coerceAtMost(1.0)
        processing = processing.scale(scaleFactor)
    }

    val type = options.type ?: originalType

    type.let {
        when (it) {
            MediaType.Image.PNG -> processing.output(PngWriter(), destinationFile)
            MediaType.Image.JPEG -> processing.output(
                JpegWriter(options.quality?.times(100)?.roundToInt() ?: 95, false),
                destinationFile
            )

            MediaType.Image.WebP -> processing.output(WebpWriter(), destinationFile)
            MediaType.Image.Tiff -> processing.output(TiffWriter(), destinationFile)
            MediaType.Image.GIF -> processing.output(GifWriter(false), destinationFile)
            MediaType.Image.BMP -> processing.output(BmpWriter(), destinationFile)
//                    MediaType.Image.APNG -> processing.output(???, out)
//                    MediaType.Image.AVIF -> processing.output(???, out)
            else -> {
                KotlinLogging.logger("com.lightningkite.lightningserver.media").warn {
                    "MediaType $it isn't supported for image processing"
                }
                return null
            }
        }
    }

    return FileMediaInfo(
        destinationFile,
        mimeType = type,
        size = destinationFile.length(),
        width = processing.width,
        height = processing.height
    )
}

/*
 * TODO: API Recommendations
 *
 * 1. Consider validating that sizeInPixels is positive and forceRatio is positive in MediaPreviewOptions.
 *    Currently, negative or zero values could cause unexpected behavior.
 *
 * 2. Consider adding support for APNG and AVIF formats, which are becoming more common for web use.
 *
 * 3. The quality parameter could have better validation/documentation. Consider enforcing 0.0-1.0 range
 *    or documenting what happens with out-of-range values.
 *
 * 4. Consider adding a callback or progress indicator for long-running image processing operations.
 *
 * 5. The resizeToRatio extension function is called but not defined in this file. Consider documenting
 *    where it comes from or making it part of this API.
 *
 * 6. Consider adding an option to preserve EXIF metadata in the output file for cases where this is desired.
 */