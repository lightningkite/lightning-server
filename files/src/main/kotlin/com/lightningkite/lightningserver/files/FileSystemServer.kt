package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.head
import com.lightningkite.lightningserver.http.put
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.trailingSegments
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.files.FileObject
import com.lightningkite.services.files.KotlinxIoPublicFileSystem
import com.lightningkite.services.files.PublicFileSystem

public class FileSystemServer(
    public val files: Runtime<PublicFileSystem>
): ServerBuilder() {
    context(runtime: ServerRuntime)
    public val rootFile: FileObject get() = files().root

    public val fetchHead: HttpHandler<PathSpec0> = path.any.head bind HttpHandler {
        val filePath = it.path.trailingSegments.toString().removePrefix("/").plus("?").plus(it.queryParametersAsString) ?: throw BadRequestException("No file to look up")
        val head = files().parseExternalUrl(files().rootUrls[0] + filePath)!!.head() ?: throw NotFoundException("No file ${filePath} found")
        HttpResponse(
            body = null,
            status = HttpStatus.NoContent,
            headers = HttpHeaders(listOf(
                HttpHeader.ContentType to head.type.toString(),
                HttpHeader.ContentLength to head.size.toString(),
            ))
        )
    }

    public val fetch: HttpHandler<PathSpec0> = path.any.get bind HttpHandler {
        val filePath = it.path.trailingSegments.toString().removePrefix("/").plus("?").plus(it.queryParametersAsString) ?: throw BadRequestException("No file to look up")
        val file = files().parseExternalUrl(files().rootUrls[0] + filePath)!!
        val range = it.headers[HttpHeader.ContentRange] ?: it.headers[HttpHeader.Range]
        if (range != null) {
            throw NotImplementedError("Range headers not yet implemented")
        } else {
            val data = file.get() ?: throw NotFoundException("No file ${filePath} found")
            HttpResponse(
                body = data
            )
        }
    }

    public val upload: HttpHandler<PathSpec0> = path.any.put bind HttpHandler {
        val filePath = it.path.trailingSegments.toString().removePrefix("/").plus("?").plus(it.queryParametersAsString)
        val kotlinx = files() as? KotlinxIoPublicFileSystem
        if (kotlinx != null) {
            kotlinx.parseUploadUrl(files().rootUrls[0] + filePath)!!.put(it.body!!)
        } else {
            throw BadRequestException("You can't upload files to this reflection of the real file system.")
        }
        HttpResponse(status = HttpStatus.NoContent)
    }
}