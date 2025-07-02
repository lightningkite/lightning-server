package com.lightningkite.lightningserver.media

import com.lightningkite.lightningserver.core.ContentType
import com.sksamuel.scrimage.ImmutableImage
import org.junit.Test
import java.io.File

class ThumbnailerTest {
    @Test
    fun processImagesWithFix() {
        val outFolder = File("../build/testoutput/exif")
        outFolder.mkdirs()
        File("../testdata/exif").listFiles().forEach { source ->
            println("Working with $source")
            val basis = try {
                ImmutableImage.loader().fromFile(source)
            } catch(e: Exception) {
                println("Cannot work with $source")
                return@forEach
            }
            listOf(
                MediaPreviewOptions(sizeInPixels = 100, type = ContentType.Image.PNG),
                MediaPreviewOptions(sizeInPixels = 100, type = ContentType.Image.WebP),
                MediaPreviewOptions(sizeInPixels = 100, type = ContentType.Image.JPEG),
                MediaPreviewOptions(),
            ).map {
                it.copy(destination = outFolder.resolve(source.nameWithoutExtension + "-${it}." + (it.type?.extension ?: source.extension)))
            }.forEach { option ->
                try {
                    if(basis.apply(option, ContentType.fromExtension(source.extension)) == null) {
                        println("Cannot make option ${option}")
                    }
                } catch(e: Exception) {
                    println("Cannot make option ${option}: ${e.message}")
                }
            }
        }
        // Manually check the images; they look good.
    }
}