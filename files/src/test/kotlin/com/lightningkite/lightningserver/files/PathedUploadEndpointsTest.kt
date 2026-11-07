package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.Engine
import com.lightningkite.lightningserver.runtime.test.testBlocking
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.sdk.FetcherSdk
import com.lightningkite.lightningserver.typed.sdk.SDK.writeUsingDefaultSettings
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.QueryParameters
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.runtime.handleRoot
import com.lightningkite.lightningserver.runtime.test.TestRunner
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.services.kfile.KFile
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.HealthStatus
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.Database
import com.lightningkite.services.files.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.serializersModuleOf
import kotlin.test.*
import kotlin.uuid.Uuid

private class AcceptingScanner(override val context: SettingContext) : FileScanner {
    override val name: String = "accepting-scanner"
    override suspend fun scan(file: ExternalFile) {}
    override suspend fun healthCheck(): HealthStatus = HealthStatus(HealthStatus.Level.OK)
}

class PathedUploadEndpointsTest {

    object Server : ServerBuilder() {
        val files = setting("files", ExternalFileSystem.Settings())
        val database = setting("database", Database.Settings())
        val uploads = path.path("uploads") include FileUploadBasis(
            files = files,
            database = database,
            fileScanner = Runtime { listOf(AcceptingScanner(contextOf<Engine>())) },
        )
        val fileEndpoints = path.path("files") include FileSystemEndpoints(files)
        val avatars = path.path("avatars").path("upload") include UploadEndpoints(uploads, "users/avatars", noAuth)
        val documents = path.path("documents").path("upload") include UploadEndpoints(uploads, "documents", noAuth)
        var nextFolder = "dynamic/first"
        val dynamic = path.path("dynamic").path("upload") include UploadEndpoints(uploads, noAuth) { nextFolder }

        val echo = path.path("echo").post bind ApiHttpHandler(
            summary = "Echo File",
            description = "Echo a ServerFile so it is marked as used and given a real url",
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
    fun uploadsLandInTheirFolder(): Unit = Server.testBlocking(settings = {
        files set ExternalFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost:8080/files")
        database set Database.Settings()
    }) {
        val prepare = avatars.upload.test(null, Unit)
        assertTrue(prepare.uploadUrl.contains("upload-jail/users/avatars/"), "Upload should target the folder in jail: ${prepare.uploadUrl}")
        val token = assertIs<UploadToken.Unscanned>(uploads.tokens().parseOrNull(prepare.token.raw))
        assertTrue(token.key.startsWith("users/avatars/"))
    }

    @Test
    fun verifyKeepsTheFolderAndTheResultIsUsable(): Unit = Server.testBlocking(settings = {
        files set ExternalFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost:8080/files")
        database set Database.Settings()
    }) {
        val prepare = avatars.upload.test(null, Unit)
        val key = uploads.tokens().parseOrNull(prepare.token.raw)!!.key
        files().root.then("upload-jail").then(key).put(TypedData.text("hello", MediaType.Text.Plain))

        val verified = avatars.verify.test(null, prepare.token)

        val promoted = assertIs<UploadToken.Scanned>(uploads.tokens().parseOrNull(verified.location)).key
        assertTrue(promoted.startsWith("users/avatars/"), "Promotion must keep the folder: $promoted")
        assertNotEquals(key, promoted)
        val ser = uploads.serializer()
        val resolved = Json { serializersModule = serializersModuleOf(ser) }.decodeFromJsonElement(ser, JsonPrimitive(verified.location))
        assertEquals("hello", resolved.externalFile.get()!!.text())
    }

    @Test
    fun verifyKeepsTheTokensFolderWhicheverModuleVerifies(): Unit = Server.testBlocking(settings = {
        files set ExternalFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost:8080/files")
        database set Database.Settings()
    }) {
        val prepare = avatars.upload.test(null, Unit)
        val key = uploads.tokens().parseOrNull(prepare.token.raw)!!.key
        files().root.then("upload-jail").then(key).put(TypedData.text("hello", MediaType.Text.Plain))

        val verified = documents.verify.test(null, prepare.token)
        val promoted = uploads.tokens().parseOrNull(verified.location)!!.key
        assertTrue(promoted.startsWith("users/avatars/"), "The file must stay where it was uploaded: $promoted")
    }

    @Test
    fun dynamicFolderIsComputedPerUpload(): Unit = Server.testBlocking(settings = {
        files set ExternalFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost:8080/files")
        database set Database.Settings()
    }) {
        Server.nextFolder = "dynamic/first"
        val first = uploads.tokens().parseOrNull(dynamic.upload.test(null, Unit).token.raw)!!.key
        Server.nextFolder = "dynamic/second"
        val second = uploads.tokens().parseOrNull(dynamic.upload.test(null, Unit).token.raw)!!.key
        assertTrue(first.startsWith("dynamic/first/"), first)
        assertTrue(second.startsWith("dynamic/second/"), second)
    }

    @Test
    fun normalUse(): Unit = Server.testBlocking({
        files set ExternalFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost:8080/files")
        database set Database.Settings()
    }) {
        val upload = avatars.upload.test(null, Unit)
        val path = PathSegments.parse(upload.uploadUrl.substringBefore('?').substringAfter("/files/"))
        fileEndpoints.upload.test(
            trailingWildcard = path,
            queryParameters = QueryParameters.parse(upload.uploadUrl.substringAfter('?')),
            body = TypedData.text("trogdor", MediaType.Text.Plain)
        )
        val fileToken = avatars.verify.test(null, upload.token)
        // Claude: raw HTTP, because .test(auth, input) encodes the token with the server's outgoing serializer
        val used = (echo as HttpHandler<PathSpec0>)
            .test(
                body = TypedData.text(Json.encodeToString(DirectServerFileSerializer, fileToken), MediaType.Application.Json)
            )
            .let {
                Json.decodeFromString(DirectServerFileSerializer, it.body!!.text())
            }

        println("Unverified Token: ${upload.token}")
        println("Verified Token: $fileToken")
        println("Used File: $used")

        val fetched = fileEndpoints.fetch.test(
            trailingWildcard = PathSegments.parse(used.location.substringBefore('?').substringAfter("/files/")),
            queryParameters = QueryParameters.parse(used.location.substringAfter('?')),
        )
        assertEquals("trogdor", fetched.body!!.text())
    }

    @Test
    fun invalidDynamicFolderFailsTheUpload(): Unit = Server.testBlocking(settings = {
        files set ExternalFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost:8080/files")
        database set Database.Settings()
    }) {
        for (bad in listOf("../escape", "a/../b", "a//b", "/abs", "trailing/", " ", "./a")) {
            Server.nextFolder = bad
            assertFailsWith<IllegalArgumentException>(bad) { dynamic.upload.test(null, Unit) }
        }
    }

    @Test
    fun folderMustBeRelative() {
        assertFailsWith<IllegalArgumentException> { UploadEndpoints(Server.uploads, "/avatars", noAuth) }
        assertFailsWith<IllegalArgumentException> { UploadEndpoints(Server.uploads, "avatars/", noAuth) }
        assertFailsWith<IllegalArgumentException> { UploadEndpoints(Server.uploads, " ", noAuth) }
        assertFailsWith<IllegalArgumentException> { UploadEndpoints(Server.uploads, "a/../b", noAuth) }
    }

    @Test
    fun sdkUsesTheUploadClient() {
        val out = "build/testfiles/sdk-${Uuid.random()}"
        FetcherSdk("com.example").writeUsingDefaultSettings(Server, KFile(out))
        val liveApi = java.io.File(out).walk().first { it.name == "LiveApi.kt" }.readText()
        assertTrue("LiveClientFileUploadEndpoints(fetcher, \"avatars/upload\"" in liveApi, liveApi)
    }

    /** The deprecated client methods decode the same JSON, so the typed tokens must not change the wire format. */
    @Suppress("DEPRECATION")
    @Test
    fun uploadTargetKeepsTheOldWireFormat() {
        val target = UploadTarget("https://example.com/put", UnverifiedUploadToken("future:a.file"))
        val old = Json.decodeFromString(UploadInformation.serializer(), Json.encodeToString(UploadTarget.serializer(), target))
        assertEquals(UploadInformation("https://example.com/put", "future:a.file"), old)
    }

    /** Over HTTP, verify must send the scanned token itself; a signed URL would never be claimed, so cleanup would delete the file. */
    @Test
    fun verifySendsTheTokenNotAUrl(): Unit = Server.testBlocking(settings = {
        files set ExternalFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost:8080/files")
        database set Database.Settings()
    }) {
        val prepare = avatars.upload.test(null, Unit)
        val key = uploads.tokens().parseOrNull(prepare.token.raw)!!.key
        files().root.then("upload-jail").then(key).put(TypedData.text("hello", MediaType.Text.Plain))

        val response = (avatars.verify as HttpHandler<PathSpec0>).test(
            body = TypedData.text(Json.encodeToString(UnverifiedUploadToken.serializer(), prepare.token), MediaType.Application.Json)
        )
        val sent = Json.decodeFromString<String>(response.body!!.text())
        assertIs<UploadToken.Scanned>(uploads.tokens().parseOrNull(sent), "Expected a scanned token, got $sent")
    }
}
