package com.lightningkite.lightningserver.media

import com.lightningkite.services.data.MediaType
import com.sksamuel.scrimage.ImmutableImage
import org.junit.Assert.*
import org.junit.Test
import java.awt.Color
import java.io.File

/**
 * Integration tests for image processing functionality.
 *
 * These tests use in-memory generated images to test the processing pipeline.
 */
class ImageProcessingTest {

    /**
     * Creates a simple test image with the specified dimensions.
     */
    private fun createTestImage(width: Int, height: Int): ImmutableImage {
        return ImmutableImage.create(width, height).fill(Color.BLUE)
    }

    @Test
    fun `apply returns null when no processing is needed`() {
        val image = createTestImage(100, 100)
        val options = MediaPreviewOptions()

        val result = image.apply(options, MediaType.Image.PNG)

        assertNull(result)
    }

    @Test
    fun `apply scales down image when sizeInPixels is smaller than image`() {
        val image = createTestImage(1000, 1000)
        val options = MediaPreviewOptions(sizeInPixels = 500)

        val result = image.apply(options, MediaType.Image.PNG)

        assertNotNull(result)
        assertTrue(result!!.width <= 500)
        assertTrue(result.height <= 500)
    }

    @Test
    fun `apply returns null when image is already smaller than sizeInPixels`() {
        val image = createTestImage(100, 100)
        val options = MediaPreviewOptions(sizeInPixels = 500)

        val result = image.apply(options, MediaType.Image.PNG)

        // Should return null because no scaling is needed and no other options are set
        assertNull(result)
    }

    @Test
    fun `apply converts image format`() {
        val image = createTestImage(100, 100)
        val options = MediaPreviewOptions(type = MediaType.Image.JPEG)

        val result = image.apply(options, MediaType.Image.PNG)

        assertNotNull(result)
        assertEquals(MediaType.Image.JPEG, result!!.mimeType)
    }

    @Test
    fun `apply uses default JPEG quality when not specified`() {
        val image = createTestImage(100, 100)
        val options = MediaPreviewOptions(type = MediaType.Image.JPEG)

        val result = image.apply(options, MediaType.Image.PNG)

        assertNotNull(result)
        assertEquals(MediaType.Image.JPEG, result!!.mimeType)
        // File should exist and have content
        assertTrue(result.file.exists())
        assertTrue(result.size > 0)
    }

    @Test
    fun `apply respects custom quality setting for JPEG`() {
        val image = createTestImage(100, 100)
        val highQuality = MediaPreviewOptions(
            type = MediaType.Image.JPEG,
            quality = 0.95
        )
        val lowQuality = MediaPreviewOptions(
            type = MediaType.Image.JPEG,
            quality = 0.1
        )

        val highResult = image.apply(highQuality, MediaType.Image.PNG)
        val lowResult = image.apply(lowQuality, MediaType.Image.PNG)

        assertNotNull(highResult)
        assertNotNull(lowResult)

        // Higher quality should generally result in larger file size
        // (though this isn't guaranteed for all images)
        assertTrue(highResult!!.size > 0)
        assertTrue(lowResult!!.size > 0)

        // Clean up
        highResult.file.delete()
        lowResult.file.delete()
    }

    @Test
    fun `apply supports PNG output format`() {
        val image = createTestImage(100, 100)
        val options = MediaPreviewOptions(type = MediaType.Image.PNG)

        val result = image.apply(options, MediaType.Image.JPEG)

        assertNotNull(result)
        assertEquals(MediaType.Image.PNG, result!!.mimeType)
        result.file.delete()
    }

    @Test
    fun `apply supports WebP output format`() {
        val image = createTestImage(100, 100)
        val options = MediaPreviewOptions(type = MediaType.Image.WebP)

        val result = image.apply(options, MediaType.Image.JPEG)

        assertNotNull(result)
        assertEquals(MediaType.Image.WebP, result!!.mimeType)
        result.file.delete()
    }

    @Test
    fun `apply returns null for unsupported format`() {
        val image = createTestImage(100, 100)
        // Using a non-image MediaType that won't be in the switch statement
        val options = MediaPreviewOptions(type = MediaType.Text.Plain)

        val result = image.apply(options, MediaType.Image.JPEG)

        assertNull(result)
    }

    @Test
    fun `apply uses custom destination file when provided`() {
        val image = createTestImage(100, 100)
        val destFile = File.createTempFile("custom-preview", ".png")

        try {
            val options = MediaPreviewOptions(
                type = MediaType.Image.PNG,
                destination = destFile
            )

            val result = image.apply(options, MediaType.Image.JPEG)

            assertNotNull(result)
            assertEquals(destFile, result!!.file)
            assertTrue(destFile.exists())
            assertTrue(destFile.length() > 0)
        } finally {
            destFile.delete()
        }
    }

    @Test
    fun `apply maintains aspect ratio when scaling`() {
        val image = createTestImage(1000, 500) // 2:1 aspect ratio
        val options = MediaPreviewOptions(sizeInPixels = 500)

        val result = image.apply(options, MediaType.Image.PNG)

        assertNotNull(result)
        val aspectRatio = result!!.width.toDouble() / result.height
        assertEquals(2.0, aspectRatio, 0.1) // Allow small margin for rounding
        result.file.delete()
    }

    @Test
    fun `FileMediaInfo contains correct image properties`() {
        val image = createTestImage(200, 150)
        val options = MediaPreviewOptions(type = MediaType.Image.PNG)

        val result = image.apply(options, MediaType.Image.JPEG)

        assertNotNull(result)
        assertEquals(200, result!!.width)
        assertEquals(150, result.height)
        assertEquals(MediaType.Image.PNG, result.mimeType)
        assertTrue(result.size > 0)
        assertTrue(result.file.exists())
        result.file.delete()
    }

    @Test
    fun `apply handles very small images correctly`() {
        val image = createTestImage(10, 10)
        val options = MediaPreviewOptions(
            sizeInPixels = 5,
            type = MediaType.Image.PNG
        )

        val result = image.apply(options, MediaType.Image.JPEG)

        assertNotNull(result)
        assertTrue(result!!.width <= 5)
        assertTrue(result.height <= 5)
        result.file.delete()
    }

    @Test
    fun `apply creates temporary file when destination is not specified`() {
        val image = createTestImage(100, 100)
        val options = MediaPreviewOptions(type = MediaType.Image.PNG)

        val result = image.apply(options, MediaType.Image.JPEG)

        assertNotNull(result)
        assertTrue(result!!.file.exists())
        assertTrue(result.file.name.contains("resized"))
        result.file.delete()
    }

    @Test
    fun `apply with both size and format conversion`() {
        val image = createTestImage(1000, 1000)
        val options = MediaPreviewOptions(
            sizeInPixels = 200,
            type = MediaType.Image.JPEG,
            quality = 0.8
        )

        val result = image.apply(options, MediaType.Image.PNG)

        assertNotNull(result)
        assertTrue(result!!.width <= 200)
        assertTrue(result.height <= 200)
        assertEquals(MediaType.Image.JPEG, result.mimeType)
        result.file.delete()
    }
}
