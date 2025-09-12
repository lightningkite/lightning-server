package com.lightningkite.lightningserver.files

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.builder.bind
import com.lightningkite.lightningserver.definition.builder.setting
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.Database
import com.lightningkite.services.files.PublicFileSystem
import com.lightningkite.services.files.ServerFile
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.uuid.Uuid

class FileSystemServerTest {

    object Server: ServerBuilder() {
        val files = setting("files", PublicFileSystem.Settings())
        val database = setting("database", Database.Settings())
        val served = path.path("files") bind  FileSystemServer(files)
        val uploadEarly = path.path("upload") bind UploadEarlyEndpoint(
            files = files,
            database = database,
            fileScanner = { listOf() }
        )
        val consume = path.path("consume").post bind ApiHttpHandler(
            summary = "OK",
            auth = noAuth,
            implementation = { file: ServerFile ->
                file
            }
        )
        init {
            registerBasicMediaTypeCoders()
        }
    }

    @Test
    fun testFetch(): Unit = runBlocking {
        Server.test(
            settings = {
                files set PublicFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost/files")
                database set Database.Settings()
            }
        ) {
            val file = files().root.then("test.txt")
            file.put(TypedData.text("Hello world!", MediaType.Text.Plain))
            val serialized = contextOf<ServerRuntime>().externalSerialization.stringArrayFormat.encodeToString(uploadEarly.serializer(), ServerFile(file.url))
            files().parseExternalUrl(serialized)!!
            val match = contextOf<ServerRuntime>().server.endpoints.match(
                contextOf<ServerRuntime>().externalSerialization.stringArrayFormat,
                serialized.substringBefore('?').substringAfter("://").substringAfter("/")
            )!!
            Server.served.fetch.test(
                trailingWildcard = match.path.trailingSegments,
                queryParameters = serialized.substringAfter('?').split('&').map { it.substringBefore('=') to it.substringAfter('=', "") },
            )
        }
    }
}