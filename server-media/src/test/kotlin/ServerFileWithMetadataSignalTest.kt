package com.lightningkite.lightningserver.media

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.db.ModelInfo
import com.lightningkite.lightningserver.db.modelInfo
import com.lightningkite.lightningserver.files.serverFile
import com.lightningkite.lightningserver.http.HttpContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.junit.Test
import com.lightningkite.UUID
import com.lightningkite.lightningserver.core.ContentType
import kotlin.test.assertEquals

@GenerateDataClassPaths
@Serializable
data class TestModelWithImage(
    override val _id: UUID = UUID.random(),
    val name: String = "Test",
    val image: ServerFileWithMetadata? = null
) : HasId<UUID>

class ServerFileWithMetadataSignalTest {

    init {
        TestSettings
    }

    val info: ModelInfo<HasId<*>?, TestModelWithImage, UUID> =
        TestSettings.database.modelInfo<HasId<*>?, TestModelWithImage, UUID>(
            authOptions = noAuth,
            permissions = {
                ModelPermissions.allowAll()
            },
            signals = {
                it.interceptImagesForProcessing(
                    MediaPreviewOptions(type = ContentType.Image.JPEG, sizeInPixels = 320),
                    MediaPreviewOptions.CorrectOddFeatures,
                ) { it.image }
            }
        )

    @Test
    fun processorTestJpg(): Unit = runBlocking {
        val file = TestSettings.files().root.resolve("test.jpg")
        file.put(HttpContent.file(java.io.File("../testdata/exif/Landscape_6.jpg")))
        val result = info.collection().insertOne(
            TestModelWithImage(
                image = ServerFileWithMetadata(file.serverFile)
            )
        )
        println("Previews: \n${result?.image?.previews?.joinToString("\n")}")
        assertEquals(2, result?.image?.previews?.size)
    }

    @Test
    fun processorTestPng(): Unit = runBlocking {
        val file = TestSettings.files().root.resolve("test.png")
        file.put(HttpContent.file(java.io.File("../testdata/exif/falconhead.png")))
        val result = info.collection().insertOne(
            TestModelWithImage(
                image = ServerFileWithMetadata(file.serverFile)
            )
        )
        println("Previews: \n${result?.image?.previews?.joinToString("\n")}")
        assertEquals(1, result?.image?.previews?.size)
    }

    @Test
    fun processorTestTxt(): Unit = runBlocking {
        val file = TestSettings.files().root.resolve("wrong-file-type.txt")
        file.put(HttpContent.file(java.io.File("../testdata/exif/wrong-file-type.txt")))
        val result = info.collection().insertOne(
            TestModelWithImage(
                image = ServerFileWithMetadata(file.serverFile)
            )
        )
        println("Previews: \n${result?.image?.previews?.joinToString("\n")}")
        assertEquals(0, result?.image?.previews?.size)
    }
}


