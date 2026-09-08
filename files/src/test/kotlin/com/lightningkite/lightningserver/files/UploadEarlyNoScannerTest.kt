package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.test.testBlocking
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.Database
import com.lightningkite.services.files.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.serializersModuleOf
import kotlin.test.*
import kotlin.uuid.Uuid

/**
 * Covers the branch taken when no scanners are configured: there is nothing to promote, so the upload
 * goes straight to the ready location and its token is born scanned.
 *
 * [UploadEarlyScanningTest] covers the other side of that condition. Without this class, inverting the
 * condition would leave both suites green.
 */
class UploadEarlyNoScannerTest {

    object Server : ServerBuilder() {
        val files = setting("files", ExternalFileSystem.Settings())
        val database = setting("database", Database.Settings())
        val uploadEarly = path.path("upload") include UploadEarlyEndpoint(
            files = files,
            database = database,
            fileScanner = { listOf() },
        )

        init {
            registerBasicMediaTypeCoders()
        }
    }

    @Test
    fun uploadGoesStraightToReadyAndTheTokenIsBornScanned(): Unit = Server.testBlocking(settings = {
        files set ExternalFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost:8080/files")
        database set Database.Settings()
    }) {
        val prepare = uploadEarly.endpoint.test(null, Unit)
        assertTrue(prepare.uploadUrl.contains("uploaded"), "Upload should target ready: ${prepare.uploadUrl}")
        assertFalse(prepare.uploadUrl.contains("upload-jail"), "Nothing to jail when nothing scans")
        assertIs<UploadToken.Scanned>(uploadEarly.tokens().parseOrNull(prepare.futureCallToken))
    }

    /** With nothing to scan there is nothing for verify to do, so a client may call it or not. */
    @Test
    fun verifyIsANoOp(): Unit = Server.testBlocking(settings = {
        files set ExternalFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost:8080/files")
        database set Database.Settings()
    }) {
        val prepare = uploadEarly.endpoint.test(null, Unit)
        assertEquals(prepare.futureCallToken, uploadEarly.verify.test(null, prepare.futureCallToken))
    }

    @Test
    fun theTokenResolvesToTheUploadedFile(): Unit = Server.testBlocking(settings = {
        files set ExternalFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost:8080/files")
        database set Database.Settings()
    }) {
        val prepare = uploadEarly.endpoint.test(null, Unit)
        val key = uploadEarly.tokens().parseOrNull(prepare.futureCallToken)!!.key
        files().root.then("uploaded").then(key).put(TypedData.text("hello", MediaType.Text.Plain))

        val ser = uploadEarly.serializer()
        val resolved = Json { serializersModule = serializersModuleOf(ser) }
            .decodeFromJsonElement(ser, JsonPrimitive(prepare.futureCallToken))
        assertEquals("hello", resolved.externalFile.get()!!.text())
    }
}
