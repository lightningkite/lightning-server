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

/**
 * HTTP endpoints for serving and uploading files backed by a PublicFileSystem.
 *
 * Endpoints:
 * - HEAD any: Returns metadata (Content-Type, Content-Length) for a file without a body.
 * - GET any: Streams the file bytes. Range requests are not yet supported.
 * - PUT any: Uploads/overwrites a file, only supported when the runtime file system is KotlinxIoPublicFileSystem.
 *
 * Gotchas:
 * - This implementation concatenates URLs using rootUrls[0] and request path/query. Prefer a URI builder for safety.
 * - Range requests are explicitly rejected at this time.
 */
public class FileSystemEndpoints(
    public val files: Runtime<PublicFileSystem>,
) : ServerBuilder() {
    /**
     * The root file/directory of the configured PublicFileSystem for the current runtime.
     */
    context(runtime: ServerRuntime)
    public val rootFile: FileObject get() = files().root

    /**
     * Builds the internal path+query portion used to address files relative to the file system's root.
     *
     * Note: This currently concatenates the query string manually. Consider using a URI builder and only
     * appending '?' when the query is non-empty to avoid dangling question marks or encoding issues.
     */
    context(runtime: ServerRuntime)
    private fun Request<*>.filePath(): String =
        // TODO: Only append '?' when the query string is non-empty; prefer proper URI building to manual concatenation.
        path.trailingSegments?.toString()?.removePrefix("/")?.plus("?")?.plus(queryParameters.toString())
            ?: throw BadRequestException("No file to look up")

    /**
     * HEAD handler. Returns metadata headers for a file without a body.
     */
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

    /**
     * GET handler. Streams the file bytes.
     *
     * TODO: Implement HTTP Range support; currently any Range header will be rejected.
     */
    public val fetch: HttpHandler<PathSpec0> = path.any.get bind HttpHandler {
        val filePath = it.filePath()
        val file = try {
            files().parseExternalUrl(files().rootUrls[0] + filePath)!!
        } catch (e: Exception) {
            throw BadRequestException("Invalid file URL")
        }
        val range = it.headers[HttpHeader.ContentRange] ?: it.headers[HttpHeader.Range]
        // TODO: "Content-Range" is a response header; request range should be taken from "Range" only. Implement partial content (206) handling.
        if (range != null) {
            throw BadRequestException("Range headers not yet implemented")
        } else {
            val data = file.get() ?: throw NotFoundException("No file $filePath found")
            HttpResponse(
                body = data
            )
        }
    }

    /**
     * PUT handler. Accepts an upload to a signed upload URL understood by KotlinxIoPublicFileSystem.
     *
     * Only KotlinxIoPublicFileSystem supports server-generated upload URLs; other implementations will
     * reject uploads here.
     */
    public val upload: HttpHandler<PathSpec0> = path.any.put bind HttpHandler {
        val kotlinx = files() as? KotlinxIoPublicFileSystem
            ?: throw BadRequestException("You can't upload files to this reflection of the real file system.")

        try {
            // TODO: Validate that request body is present; returning 400 is preferable to throwing a NullPointerException via '!!'.
            kotlinx.parseUploadUrl(files().rootUrls[0] + it.filePath())!!.put(it.body!!)
        } catch (e: Exception) {
            throw BadRequestException("Invalid file Upload URL")
        }

        HttpResponse(status = HttpStatus.NoContent)
    }

    /*
    TODO(API): Recommendations
    - Consider exposing a URI builder-based API to construct internal file URLs instead of concatenating strings (safer and encoding-correct).
    - Support HTTP Range requests on GET to enable resumable/partial downloads.
    - Consider returning 200 OK for HEAD with headers or keep 204 No Content consistently; document the choice.
    - Avoid assuming a single root URL (rootUrls[0]); expose a helper to pick the appropriate serveUrl for a generated path.
    - Provide a safer upload variant that rejects missing bodies with a 400 rather than NPE.
    */
}
