package com.lightningkite.lightningserver.media

import com.lightningkite.lightningserver.core.ContentType
import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.metadata.Orientation
import com.sksamuel.scrimage.nio.GifWriter
import com.sksamuel.scrimage.nio.JpegWriter
import com.sksamuel.scrimage.nio.PngWriter
import com.sksamuel.scrimage.nio.TiffWriter
import com.sksamuel.scrimage.webp.WebpWriter
import java.io.File
import kotlin.jvm.optionals.getOrNull
import kotlin.math.roundToInt


data class MediaPreviewOptions(
    val sizeInPixels: Int? = null,
    val forceRatio: Double? = null,
    val type: ContentType? = null,
    val quality: Double? = null,
    val destination: File? = null,
) {
    companion object {
        val CorrectOddFeatures = MediaPreviewOptions()
    }
    override fun toString(): String = listOfNotNull(
        sizeInPixels?.toString(),
        forceRatio?.let { "ratio${forceRatio}" },
        type?.let { it.extension ?: (it.type + "_" + it.subtype) },
        quality?.let { "quality${it.times(100).roundToInt()}" },
    ).let { if(it.isEmpty()) "corrected" else it.joinToString("-") }
}

fun ImmutableImage.apply(option: MediaPreviewOptions, originalType: ContentType): FileInfo? {
    val canSkip = (option.sizeInPixels == null || option.sizeInPixels > width || option.sizeInPixels > height) &&
            (option.type == null || originalType == option.type) &&
            ((option.type ?: originalType) != ContentType.Image.JPEG || this.metadata.orientation?.getOrNull().let { it == Orientation.Zero || it == null })
    if(canSkip) return null
    val basis = this
    var processing = basis
    val destinationFile = option.destination ?: File.createTempFile("resized", originalType.extension)
    option.sizeInPixels?.let { size ->
        val scaleFactor = kotlin.math.max(size / basis.width.toDouble(), size / basis.height.toDouble()).coerceAtMost(1.0)
        processing = processing.scale(scaleFactor)
    }
    option.forceRatio?.let {
        processing = processing.resizeToRatio(it)
    }
    val type = option.type ?: originalType
    type.let {
        when(it) {
            ContentType.Image.PNG -> processing.output(PngWriter(), destinationFile)
            ContentType.Image.JPEG -> processing.output(JpegWriter(option.quality?.times(100)?.roundToInt() ?: 95, false), destinationFile)
            ContentType.Image.WebP -> processing.output(WebpWriter(), destinationFile)
            ContentType.Image.Tiff -> processing.output(TiffWriter(), destinationFile)
            ContentType.Image.GIF -> processing.output(GifWriter(false), destinationFile)
//            ContentType.Image.BMP -> processing.output(BmpWriter(), destinationFile)
//                    ContentType.Image.APNG -> processing.output(???, out)
//                    ContentType.Image.AVIF -> processing.output(???, out)
            else -> {
                println("WARNING: Type $it isn't known")
                return null
            }
        }
    }
    return FileInfo(
        destinationFile,
        mimeType = type,
        size = destinationFile.length(),
        width = processing.width,
        height = processing.height
    )
}

data class FileInfo(
    val file: File,
    val mimeType: ContentType,
    val size: Long,
    val width: Int,
    val height: Int,
)