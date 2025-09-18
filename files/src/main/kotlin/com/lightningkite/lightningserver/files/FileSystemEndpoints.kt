package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.trailingSegments
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.files.FileObject
import com.lightningkite.services.files.KotlinxIoPublicFileSystem
import com.lightningkite.services.files.PublicFileSystem

public class FileSystemEndpoints(
    public val files: Runtime<PublicFileSystem>,
) : ServerBuilder() {
    context(runtime: ServerRuntime)
    public val rootFile: FileObject get() = files().root

    context(runtime: ServerRuntime)
    private fun Request<*>.filePath(): String =
        path.trailingSegments?.toString()?.removePrefix("/")?.plus("?")?.plus(queryParameters.toString())
            ?: throw BadRequestException("No file to look up")

    public val fetchHead: HttpHandler<PathSpec0> = path.any.head bind HttpHandler {
        val filePath = it.filePath()
        // We use !! on the line below since the URL in question ALWAYS matches the file system.
        // If you ever see a NPE, logic itself has broken and the universe will cease to exist shortly.
        // ... or your file system implementation is broken and doesn't recognize its own root URL.
        val head = try {
            files().parseExternalUrl(files().rootUrls[0] + filePath)!!.head()
                ?: throw NotFoundException("No file $filePath found")
        } catch (e: Exception) {
            throw BadRequestException("Invalid file URL")
        }

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
        val filePath = it.filePath()
        val file = try {
            files().parseExternalUrl(files().rootUrls[0] + filePath)!!
        } catch (e: Exception) {
            throw BadRequestException("Invalid file URL")
        }
        val range = it.headers[HttpHeader.ContentRange] ?: it.headers[HttpHeader.Range]
        if (range != null) {
            throw BadRequestException("Range headers not yet implemented")
        } else {
            val data = file.get() ?: throw NotFoundException("No file $filePath found")
            HttpResponse(
                body = data
            )
        }
    }

    public val upload: HttpHandler<PathSpec0> = path.any.put bind HttpHandler {
        val kotlinx = files() as? KotlinxIoPublicFileSystem
            ?: throw BadRequestException("You can't upload files to this reflection of the real file system.")

        try {
            kotlinx.parseUploadUrl(files().rootUrls[0] + it.filePath())!!.put(it.body!!)
        } catch (e: Exception) {
            throw BadRequestException("Invalid file Upload URL")
        }

        HttpResponse(status = HttpStatus.NoContent)
    }
}