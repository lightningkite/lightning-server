package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.services.database.Database
import com.lightningkite.services.files.ExternalFileSystem
import com.lightningkite.services.files.ServerFile
import com.lightningkite.services.files.serverFile
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class FilesModuleReviewTests {

    private object Server : ServerBuilder() {
        val files = setting("files", ExternalFileSystem.Settings())
        val database = setting("database", Database.Settings())
        val served = path.path("files") include FileSystemEndpoints(files)
        val uploadEarly = path.path("upload") include UploadEarlyEndpoint(
            files = files,
            database = database,
            fileScanner = { listOf() }
        )
        val consume = path.path("consume").post bind ApiHttpHandler(
            summary = "OK",
            auth = noAuth,
            implementation = { file: ServerFile -> file }
        )

        init {
            registerBasicMediaTypeCoders()
        }
    }


    @Test
    fun nameWithoutExtension_basic(): Unit = runBlocking {
        Server.test(
            settings = {
                files set ExternalFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost/files")
                database set Database.Settings()
            }
        ) {
            val fo = files().root.then("helpers-test.txt")
            assertEquals("helpers-test", fo.nameWithoutExtension)
            val fo2 = files().root.then("noext")
            assertEquals("noext", fo2.nameWithoutExtension)
        }
    }

    @Test
    fun serverFile_to_fileObject_roundTrip(): Unit = runBlocking {
        Server.test(
            settings = {
                files set ExternalFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost/files")
                database set Database.Settings()
            }
        ) {
            val fo = files().root.then("roundtrip.txt")
            val sf = fo.serverFile
            val back = sf.fileObject
            assertEquals(fo, back)
        }
    }
}
