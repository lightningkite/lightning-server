package com.lightningkite.lightningserver.media

import com.lightningkite.services.data.MediaType
import com.lightningkite.services.files.ServerFile
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for ServerFileWithMetadata and ServerFileWithMetadataPreview data classes.
 */
class ServerFileWithMetadataTest {

    @Test
    fun `previews method filters by supported types`() {
        val metadata = ServerFileWithMetadata(
            original = ServerFile("test.jpg"),
            previews = listOf(
                ServerFileWithMetadataPreview(
                    file = ServerFile("test-thumb.jpg"),
                    mimeType = MediaType.Image.JPEG,
                    size = 1024,
                    width = 100,
                    height = 100
                ),
                ServerFileWithMetadataPreview(
                    file = ServerFile("test-thumb.png"),
                    mimeType = MediaType.Image.PNG,
                    size = 2048,
                    width = 100,
                    height = 100
                ),
                ServerFileWithMetadataPreview(
                    file = ServerFile("test-thumb.webp"),
                    mimeType = MediaType.Image.WebP,
                    size = 512,
                    width = 100,
                    height = 100
                )
            )
        )

        val supportedTypes = setOf(MediaType.Image.JPEG, MediaType.Image.PNG)
        val results = metadata.previews(supportedTypes).toList()

        assertEquals(2, results.size)
        assertTrue(results.all { it.mimeType in supportedTypes })
    }

    @Test
    fun `previews method returns empty sequence when no types match`() {
        val metadata = ServerFileWithMetadata(
            original = ServerFile("test.jpg"),
            previews = listOf(
                ServerFileWithMetadataPreview(
                    file = ServerFile("test-thumb.jpg"),
                    mimeType = MediaType.Image.JPEG,
                    size = 1024,
                    width = 100,
                    height = 100
                )
            )
        )

        val supportedTypes = setOf(MediaType.Image.PNG, MediaType.Image.WebP)
        val results = metadata.previews(supportedTypes).toList()

        assertEquals(0, results.size)
    }

    @Test
    fun `previews method prioritizes exact dimension matches`() {
        val metadata = ServerFileWithMetadata(
            original = ServerFile("test.jpg"),
            previews = listOf(
                ServerFileWithMetadataPreview(
                    file = ServerFile("test-small.jpg"),
                    mimeType = MediaType.Image.JPEG,
                    size = 512,
                    width = 50,
                    height = 50
                ),
                ServerFileWithMetadataPreview(
                    file = ServerFile("test-medium.jpg"),
                    mimeType = MediaType.Image.JPEG,
                    size = 1024,
                    width = 200,
                    height = 200
                ),
                ServerFileWithMetadataPreview(
                    file = ServerFile("test-large.jpg"),
                    mimeType = MediaType.Image.JPEG,
                    size = 2048,
                    width = 500,
                    height = 500
                )
            )
        )

        val supportedTypes = setOf(MediaType.Image.JPEG)
        val results = metadata.previews(
            supportedTypes,
            preferredMinimumWidth = 200,
            preferredMinimumHeight = 200
        ).toList()

        // Should return medium first (exact match), then large, then small (with penalty)
        assertEquals(3, results.size)
        assertEquals(200, results[0].width)
    }

    @Test
    fun `previews method penalizes undersized images`() {
        val metadata = ServerFileWithMetadata(
            original = ServerFile("test.jpg"),
            previews = listOf(
                ServerFileWithMetadataPreview(
                    file = ServerFile("test-small.jpg"),
                    mimeType = MediaType.Image.JPEG,
                    size = 512,
                    width = 50,
                    height = 50
                ),
                ServerFileWithMetadataPreview(
                    file = ServerFile("test-oversized.jpg"),
                    mimeType = MediaType.Image.JPEG,
                    size = 4096,
                    width = 250,
                    height = 250
                )
            )
        )

        val supportedTypes = setOf(MediaType.Image.JPEG)
        val results = metadata.previews(
            supportedTypes,
            preferredMinimumWidth = 200,
            preferredMinimumHeight = 200
        ).toList()

        // Oversized (250x250) should come before undersized (50x50) due to penalty
        assertEquals(2, results.size)
        assertEquals(250, results[0].width)
        assertEquals(50, results[1].width)
    }

    @Test
    fun `previews method handles null dimensions gracefully`() {
        val metadata = ServerFileWithMetadata(
            original = ServerFile("test.mp4"),
            previews = listOf(
                ServerFileWithMetadataPreview(
                    file = ServerFile("test-preview.jpg"),
                    mimeType = MediaType.Image.JPEG,
                    size = 1024,
                    width = null,
                    height = null
                )
            )
        )

        val supportedTypes = setOf(MediaType.Image.JPEG)
        val results = metadata.previews(
            supportedTypes,
            preferredMinimumWidth = 200,
            preferredMinimumHeight = 200
        ).toList()

        // Should still return the preview even though dimensions are null
        assertEquals(1, results.size)
    }

    @Test
    fun `ServerFileWithMetadata can have no previews`() {
        val metadata = ServerFileWithMetadata(
            original = ServerFile("test.jpg"),
            mimeType = MediaType.Image.JPEG,
            size = 10240,
            width = 1920,
            height = 1080
        )

        assertEquals(0, metadata.previews.size)
    }

    @Test
    fun `ServerFileWithMetadata properties can be null`() {
        val metadata = ServerFileWithMetadata(
            original = ServerFile("test.unknown")
        )

        assertNull(metadata.mimeType)
        assertNull(metadata.size)
        assertNull(metadata.width)
        assertNull(metadata.height)
    }
}
