package com.lightningkite.lightningserver.files

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.QueryParameters
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.serverRuntime
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
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class FileSystemEndpointsTest {

    object Server: ServerBuilder() {
        val files = setting("files", PublicFileSystem.Settings())
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
            println(file.url)
            println(file.signedUrl)
            val serialized = serverRuntime.externalSerialization.stringArrayFormat.encodeToString(uploadEarly.serializer(), ServerFile(file.url))
            println("Serialized: $serialized")
            files().parseExternalUrl(serialized)!!
            println("Url parse successful")
            val match = serverRuntime.server.endpoints.match(
                serverRuntime.externalSerialization.stringArrayFormat,
                serialized.substringBefore('?').substringAfter("://").substringAfter("/")
            ) { it.http[HttpMethod.GET] }!!
            Server.served.fetch.test(
                trailingWildcard = match.path.trailingSegments,
                queryParameters = QueryParameters.parse(serialized.substringAfter('?')),
            )
        }
    }

    @Test
    fun testRangeParsing() {
        assertEquals(
            listOf(
                HttpRange.Bounded(5, 10)
            ),
            HttpHeaders(
                HttpHeader.Range to "bytes=5-10"
            ).httpRanges()
        )

        assertEquals(
            listOf(
                HttpRange.Bounded(5, 10),
                HttpRange.Bounded(15, 20)
            ),
            HttpHeaders(
                HttpHeader.Range to "bytes=5-10,15-20"
            ).httpRanges()
        )

        assertEquals(
            listOf(
                HttpRange.Last(100),
                HttpRange.UntilEnd(200),
            ),
            HttpHeaders(
                HttpHeader.Range to "bytes=-100,200-"
            ).httpRanges()
        )

        assertEquals(
            null,
            HttpHeaders(
                HttpHeader.Range to "bytes=-"
            ).httpRanges()
        )
    }

    @Test
    fun rangeRequests(): Unit = runBlocking {
        Server.test(
            settings = {
                files set PublicFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost/files")
                database set Database.Settings()
            }
        ) {
            val file = files().root.then("test.txt")
            file.put(
                TypedData.text(List(1000) { it }.joinToString(""), MediaType.Text.Plain)
            )

            val serialized = serverRuntime.externalSerialization.stringArrayFormat.encodeToString(uploadEarly.serializer(), ServerFile(file.url))

            files().parseExternalUrl(serialized)!!

            val match = serverRuntime.server.endpoints.match(
                serverRuntime.externalSerialization.stringArrayFormat,
                serialized.substringBefore('?').substringAfter("://").substringAfter("/")
            ) { it.http[HttpMethod.GET] }!!

            suspend fun testRange(header: String) {
                val response = Server.served.fetch.test(
                    trailingWildcard = match.path.trailingSegments,
                    queryParameters = QueryParameters.parse(serialized.substringAfter('?')),
                    headers = HttpHeaders(
                        HttpHeader.Range to "bytes=$header"
                    )
                )

                println("$header -> ${response.body?.text()}")
            }

            testRange("0-100")
            testRange("50-100")
            testRange("900-")
            testRange("-10")
            testRange("0-10, 30-50, 70-90, -10")
        }
    }
}