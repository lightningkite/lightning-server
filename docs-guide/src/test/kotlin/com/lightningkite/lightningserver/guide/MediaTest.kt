package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.GalleryPhoto
import com.lightningkite.lightningserver.guide.samples.GalleryServer
import com.lightningkite.lightningserver.guide.samples.thumbnailFor
import com.lightningkite.lightningserver.media.ServerFileWithMetadata
import com.lightningkite.lightningserver.runtime.test.testBlocking
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.Database
import com.lightningkite.services.files.ExternalFileSystem
import com.lightningkite.services.files.serverFile
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class MediaTest {
    private fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()

    @Test
    fun `inserting a photo records metadata and writes a thumbnail`() = GalleryServer.testBlocking(settings = {
        files set ExternalFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost:8080/files")
        database set Database.Settings()
    }) {
        val original = GalleryServer.files().root.then("photos/beach.png")
        original.put(TypedData.bytes(png(400, 300), MediaType.Image.PNG))

        val saved = GalleryServer.photos.insert.test(
            null,
            GalleryPhoto(caption = "Beach", image = ServerFileWithMetadata(original = original.serverFile)),
        )
        val image = saved.image!!
        assertEquals(400, image.width)
        assertEquals(300, image.height)
        assertEquals(MediaType.Image.PNG, image.mimeType)

        // The 1200 px option changes nothing for a 400 px PNG, so only the thumbnail is written.
        val thumbnail = image.previews.single()
        assertEquals(MediaType.Image.JPEG, thumbnail.mimeType)
        // The shorter side is scaled to sizeInPixels.
        assertEquals(200, thumbnail.height)
        assertEquals(266, thumbnail.width)
        assertEquals(thumbnail.file, thumbnailFor(image))
    }
}
