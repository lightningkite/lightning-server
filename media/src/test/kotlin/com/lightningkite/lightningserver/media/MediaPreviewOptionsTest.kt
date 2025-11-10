package com.lightningkite.lightningserver.media

import com.lightningkite.MediaType
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Tests for MediaPreviewOptions configuration and string representation.
 */
class MediaPreviewOptionsTest {

    @Test
    fun `MediaPreviewOptions toString with all options returns formatted string`() {
        val options = MediaPreviewOptions(
            sizeInPixels = 800,
            forceRatio = 16.0 / 9.0,
            type = MediaType.Image.JPEG,
            quality = 0.85
        )

        val result = options.toString()

        // The toString creates a hyphen-separated list of options
        assertTrue("Expected '800' in '$result'", result.contains("800"))
        assertTrue("Expected 'ratio' in '$result'", result.contains("ratio"))
        // Note: The exact extension returned by MediaType.Image.JPEG.extension depends on the MediaType implementation
        assertTrue("Expected 'quality85' in '$result'", result.contains("quality85"))
        assertFalse("Should not be 'corrected'", result.equals("corrected"))
    }

    @Test
    fun `MediaPreviewOptions toString with no options returns corrected`() {
        val options = MediaPreviewOptions()

        assertEquals("corrected", options.toString())
    }

    @Test
    fun `MediaPreviewOptions toString with only size`() {
        val options = MediaPreviewOptions(sizeInPixels = 1024)

        assertEquals("1024", options.toString())
    }

    @Test
    fun `MediaPreviewOptions toString with size and quality`() {
        val options = MediaPreviewOptions(
            sizeInPixels = 512,
            quality = 0.75
        )

        val result = options.toString()
        assertTrue(result.contains("512"))
        assertTrue(result.contains("quality75"))
    }

    @Test
    fun `CorrectOddFeatures constant has no transformations`() {
        val options = MediaPreviewOptions.CorrectOddFeatures

        assertNull(options.sizeInPixels)
        assertNull(options.forceRatio)
        assertNull(options.type)
        assertNull(options.quality)
        assertNull(options.destination)
    }

    @Test
    fun `CorrectOddFeatures toString returns corrected`() {
        assertEquals("corrected", MediaPreviewOptions.CorrectOddFeatures.toString())
    }

    @Test
    fun `MediaPreviewOptions with custom destination file`() {
        val destFile = File.createTempFile("test", ".jpg")
        try {
            val options = MediaPreviewOptions(
                sizeInPixels = 800,
                destination = destFile
            )

            assertEquals(destFile, options.destination)
        } finally {
            destFile.delete()
        }
    }

    @Test
    fun `MediaPreviewOptions quality rounds to nearest integer in toString`() {
        val options1 = MediaPreviewOptions(quality = 0.876)
        assertTrue(options1.toString().contains("quality88"))

        val options2 = MediaPreviewOptions(quality = 0.123)
        assertTrue(options2.toString().contains("quality12"))
    }

    @Test
    fun `MediaPreviewOptions forceRatio is included in toString`() {
        val options = MediaPreviewOptions(forceRatio = 1.77)

        val result = options.toString()
        assertTrue(result.contains("ratio1.77"))
    }

    @Test
    fun `MediaPreviewOptions with all null values equals CorrectOddFeatures behavior`() {
        val options = MediaPreviewOptions(
            sizeInPixels = null,
            forceRatio = null,
            type = null,
            quality = null,
            destination = null
        )

        assertEquals("corrected", options.toString())
    }

    @Test
    fun `MediaPreviewOptions data class copy works correctly`() {
        val original = MediaPreviewOptions(
            sizeInPixels = 800,
            quality = 0.9
        )

        val copy = original.copy(sizeInPixels = 1024)

        assertEquals(1024, copy.sizeInPixels)
        assertEquals(0.9, copy.quality!!, 0.001)
    }

    @Test
    fun `MediaPreviewOptions equality works as expected`() {
        val options1 = MediaPreviewOptions(
            sizeInPixels = 800,
            forceRatio = 1.5,
            type = MediaType.Image.PNG,
            quality = 0.8
        )

        val options2 = MediaPreviewOptions(
            sizeInPixels = 800,
            forceRatio = 1.5,
            type = MediaType.Image.PNG,
            quality = 0.8
        )

        val options3 = MediaPreviewOptions(
            sizeInPixels = 800,
            forceRatio = 1.5,
            type = MediaType.Image.JPEG,
            quality = 0.8
        )

        assertEquals(options1, options2)
        assertNotEquals(options1, options3)
    }
}
