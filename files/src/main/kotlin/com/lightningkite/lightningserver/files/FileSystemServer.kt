package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.builder.bind
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.trailingSegments
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.files.FileObject
import com.lightningkite.services.files.KotlinxIoPublicFileSystem
import com.lightningkite.services.files.PublicFileSystem

public class FileSystemServer(
    public val files: Runtime<PublicFileSystem>,
) : ServerBuilder() {
    context(runtime: ServerRuntime)
    public val rootFile: FileObject get() = files().root

    public val fetchHead: HttpHandler<PathSpec0> = path.any.head bind HttpHandler {
        val filePath = it.path.trailingSegments.toString().removePrefix("/").plus("?").plus(it.queryParametersAsString)
        val head = try {
            files().parseExternalUrl(files().rootUrls[0] + filePath)!!.head()
        } catch (e: Exception) {
            throw BadRequestException("Invalid file URL")
        }
            ?: throw NotFoundException("No file ${filePath} found")
        HttpResponse(
            body = null,
            status = HttpStatus.NoContent,
            headers = HttpHeaders(
                listOf(
                    HttpHeader.ContentType to head.type.toString(),
                    HttpHeader.ContentLength to head.size.toString(),
                )
            )
        )
    }

    public val fetch: HttpHandler<PathSpec0> = path.any.get bind HttpHandler {
        val filePath = it.path.trailingSegments.toString().removePrefix("/").plus("?").plus(it.queryParametersAsString)
        val file = try {
            files().parseExternalUrl(files().rootUrls[0] + filePath)!!
        } catch (e: Exception) {
            throw BadRequestException("Invalid file URL")
        }
        val range = it.headers[HttpHeader.ContentRange] ?: it.headers[HttpHeader.Range]
        if (range != null) {
            throw BadRequestException("Range headers not yet implemented")
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
            try {
                kotlinx.parseUploadUrl(files().rootUrls[0] + filePath)!!.put(it.body!!)
            } catch (e: Exception) {
                throw BadRequestException("Invalid file Upload URL")
            }
        } else {
            throw BadRequestException("You can't upload files to this reflection of the real file system.")
        }
        HttpResponse(status = HttpStatus.NoContent)
    }
}