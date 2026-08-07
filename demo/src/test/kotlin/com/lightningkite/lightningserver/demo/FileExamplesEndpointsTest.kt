package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.demo.endpoints.UploadFileRequest
import com.lightningkite.lightningserver.demo.endpoints.UploadImageRequest
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.runtime.test.TestRunner
import com.lightningkite.lightningserver.runtime.test.testBlocking
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.database.Database
import com.lightningkite.services.files.ExternalFileSystem
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class FileExamplesEndpointsTest {

    private fun fileTest(action: suspend context(TestRunner<Server>) Server.() -> Unit) =
        Server.testBlocking(
            settings = {
                database set Database.Settings("ram")
                files set ExternalFileSystem.Settings(
                    "file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost:8080/files"
                )
            },
            action = action,
        )

    @Test
    fun uploadThenReadBackTheSameContent() = fileTest {
        val upload = Server.fileExamples.uploadFile.test(null, UploadFileRequest(fileName = "hello.txt", content = "Hello, files!"))

        assertTrue(upload.signedUrl.isNotBlank())
        assertEquals("Hello, files!".encodeToByteArray().size.toLong(), upload.fileSize)

        val info = Server.fileExamples.getFileInfo.test("hello.txt", null, Unit)
        assertEquals(upload.fileSize, info.sizeBytes)

        val signed = Server.fileExamples.getSignedUrl.test("hello.txt", null, Unit)
        assertTrue(signed.url.isNotBlank())
    }

    @Test
    fun infoForAMissingFileIs404() = fileTest {
        val exception = assertFailsWith<HttpStatusException> {
            Server.fileExamples.getFileInfo.test("nonexistent.txt", null, Unit)
        }
        assertEquals(HttpStatus.NotFound, exception.status)
    }

    @Test
    fun deleteRemovesTheFile() = fileTest {
        Server.fileExamples.uploadFile.test(null, UploadFileRequest(fileName = "temp.txt", content = "gone soon"))
        Server.fileExamples.deleteFile.test("temp.txt", null, Unit)

        assertFailsWith<HttpStatusException> {
            Server.fileExamples.getFileInfo.test("temp.txt", null, Unit)
        }
    }

    @Test
    fun deletingAMissingFileIs404() = fileTest {
        assertFailsWith<HttpStatusException> {
            Server.fileExamples.deleteFile.test("nonexistent.txt", null, Unit)
        }
    }

    @Test
    fun uploadImageStoresRealBytes() = fileTest {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val response = Server.fileExamples.uploadImage.test(
            null,
            UploadImageRequest(
                fileName = "pixel.png",
                contentBase64 = Base64.Default.encode(bytes),
                mimeType = "image/png",
            )
        )

        assertEquals(bytes.size.toLong(), response.fileSize)
        assertTrue(response.signedUrl.isNotBlank())
    }

    @Test
    fun uploadImageRejectsNonImageMimeTypes() = fileTest {
        assertFailsWith<Exception> {
            Server.fileExamples.uploadImage.test(
                null,
                UploadImageRequest(
                    fileName = "not-an-image.txt",
                    contentBase64 = Base64.Default.encode(byteArrayOf(1)),
                    mimeType = "text/plain",
                )
            )
        }
    }
}
