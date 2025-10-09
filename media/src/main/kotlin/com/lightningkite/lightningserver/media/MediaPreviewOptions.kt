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
import kotlin.math.roundToInt

public data class MediaPreviewOptions(
    /**The maximum size allowed for both width and height*/
    val sizeInPixels: Int? = null,
    val forceRatio: Double? = null,
    val type: MediaType? = null,
    val quality: Double? = null,
    val destination: File? = null,
) {
    public companion object {
        public val CorrectOddFeatures: MediaPreviewOptions = MediaPreviewOptions()
    }

    override fun toString(): String = listOfNotNull(
        sizeInPixels?.toString(),
        forceRatio?.let { "ratio${forceRatio}" },
        type?.extension,
        quality?.let { "quality${it.times(100).roundToInt()}" },
    ).let { if (it.isEmpty()) "corrected" else it.joinToString("-") }
}

public data class FileMediaInfo(
    val file: File,
    val mimeType: MediaType,
    val size: Long,
    val width: Int,
    val height: Int,
)

public fun ImmutableImage.apply(
    options: MediaPreviewOptions,
    originalType: MediaType,
): FileMediaInfo? {
    val needsScaling = options.sizeInPixels != null && (options.sizeInPixels < width || options.sizeInPixels < height)
    val needsRatio = options.forceRatio != null && width.toDouble() / height != options.forceRatio
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

    if (needsScaling && (!needsRatio || options.sizeInPixels < processing.width || options.sizeInPixels < processing.height)) {
        val scaleFactor =
            kotlin.math.max(
                options.sizeInPixels / basis.width.toDouble(),
                options.sizeInPixels / basis.height.toDouble()
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