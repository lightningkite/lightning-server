package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.FileServer
import com.lightningkite.lightningserver.guide.samples.ReportServer
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.lightningserver.http.QueryParameters
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.runtime.test.testBlocking
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.Database
import com.lightningkite.services.files.DirectServerFileSerializer
import com.lightningkite.services.files.ExternalFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class FilesTest {
    private fun localFiles() =
        ExternalFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost:8080/files")

    @Test
    fun `upload, put, verify, then submit`() = FileServer.testBlocking(settings = {
        files set localFiles()
        database set Database.Settings()
    }) {
        val target = FileServer.documentUpload.upload.test(null, Unit)

        // The client PUTs to uploadUrl; locally that lands on FileSystemEndpoints.
        val put = FileServer.served.upload.test(
            trailingWildcard = PathSegments.parse(target.uploadUrl.substringBefore('?').substringAfter("/files/")),
            queryParameters = QueryParameters.parse(target.uploadUrl.substringAfter('?')),
            body = TypedData.text("%PDF-1.4 fake", MediaType.Application.Pdf),
        )
        assertEquals(HttpStatus.NoContent, put.status)

        val verified = FileServer.documentUpload.verify.test(null, target.token)

        // Raw HTTP: the client sends the verified token as the file's value.
        val body = JsonObject(mapOf(
            "title" to JsonPrimitive("Contract"),
            "file" to JsonPrimitive(verified.location),
        ))
        val response = (FileServer.submit as HttpHandler<PathSpec0>).test(
            body = TypedData.text(body.toString(), MediaType.Application.Json)
        )
        val responseText = response.body!!.text()
        assertEquals(HttpStatus.OK, response.status, responseText)

        // On the way out the file becomes a signed URL served by FileSystemEndpoints.
        val url = Json.decodeFromString(DirectServerFileSerializer, responseText).location
        assertTrue(url.startsWith("http://localhost:8080/files/uploaded/documents/"), url)
    }

    @Test
    fun `MimeType rejects the wrong type`() = FileServer.testBlocking(settings = {
        files set localFiles()
        database set Database.Settings()
    }) {
        val target = FileServer.documentUpload.upload.test(null, Unit)
        FileServer.served.upload.test(
            trailingWildcard = PathSegments.parse(target.uploadUrl.substringBefore('?').substringAfter("/files/")),
            queryParameters = QueryParameters.parse(target.uploadUrl.substringAfter('?')),
            body = TypedData.text("hello", MediaType.Text.Plain),
        )
        val verified = FileServer.documentUpload.verify.test(null, target.token)
        val body = JsonObject(mapOf("title" to JsonPrimitive("x"), "file" to JsonPrimitive(verified.location)))
        val response = (FileServer.submit as HttpHandler<PathSpec0>).test(
            body = TypedData.text(body.toString(), MediaType.Application.Json)
        )
        assertEquals(HttpStatus.BadRequest, response.status)
    }

    @Test
    fun `direct file access`() = ReportServer.testBlocking(settings = { files set localFiles() }) {
        val url = ReportServer.writeReport.test(null, "quarterly numbers")
        assertTrue(url.contains("/reports/report-"), url)
    }
}
