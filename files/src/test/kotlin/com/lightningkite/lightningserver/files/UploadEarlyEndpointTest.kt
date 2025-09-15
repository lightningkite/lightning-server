package com.lightningkite.lightningserver.files

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.Database
import com.lightningkite.services.files.PublicFileSystem
import com.lightningkite.services.files.ServerFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.serializersModuleOf
import kotlin.test.Test
import kotlin.uuid.Uuid

class UploadEarlyEndpointTest {

    object Server: ServerBuilder() {
        val files = setting("files", PublicFileSystem.Settings())
        val database = setting("database", Database.Settings())
        val served = path.path("files") include  FileSystemServer(files)
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

    @Test fun contextualUsage(): Unit {
        val json = Json {
            serializersModule = serializersModuleOf(ServerFile::class, object: KSerializer<ServerFile> {
                override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ServerFile", PrimitiveKind.STRING)

                override fun serialize(
                    encoder: Encoder,
                    value: ServerFile
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
                files set PublicFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost/files")
                database set Database.Settings()
            }
        ) {
            val file = files().root.then("test.txt")
            file.put(TypedData.text("Hello world!", MediaType.Text.Plain))
            val serialized = contextOf<ServerRuntime>().externalSerialization.stringArrayFormat.encodeToString(uploadEarly.serializer(), ServerFile(file.url))
            files().parseExternalUrl(serialized)!!
            run {
                val match = contextOf<ServerRuntime>().server.endpoints.match(
                    contextOf<ServerRuntime>().externalSerialization.stringArrayFormat,
                    serialized.substringBefore('?').substringAfter("://").substringAfter("/")
                )!!
                Server.served.fetch.test(
                    trailingWildcard = match.path.trailingSegments,
                    queryParameters = serialized.substringAfter('?').split('&')
                        .map { it.substringBefore('=') to it.substringAfter('=', "") },
                )
            }
        }
    }
    @Test
    fun test(): Unit = runBlocking {
        Server.test(
            settings = {
                files set PublicFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost/files")
                database set Database.Settings()
            }
        ) {
            val prepare = Server.uploadEarly.endpoint.test(null, Unit)

            run {
                val match = contextOf<ServerRuntime>().server.endpoints.match(
                    contextOf<ServerRuntime>().externalSerialization.stringArrayFormat,
                    prepare.uploadUrl.substringBefore('?').substringAfter("://").substringAfter("/")
                )!!
                Server.served.upload.test(
                    trailingWildcard = match.path.trailingSegments,
                    queryParameters = prepare.uploadUrl.substringAfter('?').split('&')
                        .map { it.substringBefore('=') to it.substringAfter('=', "") },
                    body = TypedData.text("Hello world!", MediaType.Text.Plain)
                )
            }
            val ready = Server.uploadEarly.verify.test(null, prepare.futureCallToken)

            val clientSideServerFile = Server.consume.test(body = TypedData.text(
                Json.encodeToString(ServerFile(ready)),
                MediaType.Application.Json
            ))
                .also { assert(it.status.success) }
                .body!!
                .text()
                .let { Json.decodeFromString<ServerFile>(it) }

            run {
                val match = contextOf<ServerRuntime>().server.endpoints.match(
                    contextOf<ServerRuntime>().externalSerialization.stringArrayFormat,
                    clientSideServerFile.location.substringBefore('?').substringAfter("://").substringAfter("/")
                )!!
                Server.served.fetch.test(
                    trailingWildcard = match.path.trailingSegments,
                    queryParameters = clientSideServerFile.location.substringAfter('?', "").split('&')
                        .map { it.substringBefore('=') to it.substringAfter('=', "") },
                )
            }
        }
    }
}