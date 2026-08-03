package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.QueryParameters
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.Database
import com.lightningkite.services.files.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.serializersModuleOf
import kotlin.test.Test
import kotlin.uuid.Uuid

class UploadEarlyEndpointTest {

    object Server : ServerBuilder() {
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
            implementation = { file: ServerFile ->
                file
            }
        )

        init {
            registerBasicMediaTypeCoders()
        }
    }

    @Test
    fun contextualUsage(): Unit {
        val json = Json {
            serializersModule = serializersModuleOf(ServerFile::class, object : KSerializer<ServerFile> {
                override val descriptor: SerialDescriptor =
                    PrimitiveSerialDescriptor("com.lightningkite.services.files.ServerFile/EarlyUpload", PrimitiveKind.STRING)

                override fun serialize(
                    encoder: Encoder,
                    value: ServerFile,
                ) {
                    encoder.encodeString("SF: " + value.location)
                }

                override fun deserialize(decoder: Decoder): ServerFile {
                    return decoder.decodeString().substringAfter("SF: ").let { ServerFile(it) }
                }

            })
        }
        println("without: " + Json.encodeToString(ServerFile("test")))
        println("with: " + json.encodeToString(ServerFile("test")))
    }

    @Test
    fun testServed(): Unit = runBlocking {
        Server.test(
            settings = {
                files set ExternalFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost:8080/files")
                database set Database.Settings()
            }
        ) {
            println((files() as KotlinxIoExternalFileSystem).serveUrl)
            val file = files().root.then("test.txt")
            file.put(TypedData.text("Hello world!", MediaType.Text.Plain))
            println(file.signedUrl)
            val serialized = contextOf<ServerRuntime>().externalSerialization.stringArrayFormat.encodeToString(
                uploadEarly.serializer(),
                file.serverFile
            )
            println(serialized)
            files().parseExternalUrl(serialized)!!
            run {
                val match = contextOf<ServerRuntime>().server.endpoints.match(
                    contextOf<ServerRuntime>().externalSerialization.stringArrayFormat,
                    serialized.substringBefore('?').substringAfter("://").substringAfter("/")
                ) { it.http[HttpMethod.GET] } ?: throw Exception(
                    "Endpoint for '${
                        serialized.substringBefore('?').substringAfter("://").substringAfter("/")
                    }' not found"
                )
                Server.served.fetch.test(
                    trailingWildcard = match.path.trailingSegments,
                    queryParameters = serialized.substringAfter('?').let { QueryParameters.parse(it) },
                )
            }
        }
    }

    @Test
    fun test(): Unit = runBlocking {
        Server.test(
            settings = {
                files set ExternalFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost:8080/files")
                database set Database.Settings()
            }
        ) {
            val prepare = Server.uploadEarly.endpoint.test(null, Unit)

            run {
                val match = contextOf<ServerRuntime>().server.endpoints.match(
                    contextOf<ServerRuntime>().externalSerialization.stringArrayFormat,
                    prepare.uploadUrl.substringBefore('?').substringAfter("://").substringAfter("/")
                ) { it.http[HttpMethod.PUT] }!!
                Server.served.upload.test(
                    trailingWildcard = match.path.trailingSegments,
                    queryParameters = prepare.uploadUrl.substringAfter('?').let { QueryParameters.parse(it) },
                    body = TypedData.text("Hello world!", MediaType.Text.Plain)
                )
            }
            val ready = Server.uploadEarly.verify.test(null, prepare.futureCallToken)

            val clientSideServerFile = Server.consume.test(
                body = TypedData.text(
                    Json.encodeToString(ServerFile(ready)),
                    MediaType.Application.Json
                )
            )
                .also { assert(it.status.success) }
                .body!!
                .text()
                .let { Json.decodeFromString<ServerFile>(it) }

            run {
                val match = contextOf<ServerRuntime>().server.endpoints.match(
                    contextOf<ServerRuntime>().externalSerialization.stringArrayFormat,
                    clientSideServerFile.location.substringBefore('?').substringAfter("://").substringAfter("/")
                ) { it.http[HttpMethod.GET] }!!
                Server.served.fetch.test(
                    trailingWildcard = match.path.trailingSegments,
                    queryParameters = clientSideServerFile.location.substringAfter('?')
                        .let { QueryParameters.parse(it) },
                )
            }
        }
    }
}