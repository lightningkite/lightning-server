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
import com.lightningkite.services.files.*

/**
 * HTTP endpoints for serving and uploading files backed by an ExternalFileSystem.
 *
 * Endpoints:
 * - HEAD any: Returns metadata (Content-Type, Content-Length) for a file without a body.
 * - GET any: Streams the file bytes, honoring Range requests.
 * - PUT any: Uploads/overwrites a file.
 *
 * These endpoints serve the signed URLs of a [KotlinxIoExternalFileSystem], the only implementation whose
 * files are served by this server rather than by the storage backend itself; other backends serve (and
 * sign) their own URLs and never route here.
 */
public class FileSystemEndpoints(
    public val files: Runtime<ExternalFileSystem>,
    public val malformedRanges: HttpRange.MalformedBehavior = HttpRange.MalformedBehavior.IgnoreRangeRequest,
) : ServerBuilder() {
    /**
     * The root file/directory of the configured ExternalFileSystem for the current runtime.
     */
    context(runtime: ServerRuntime)
    public val rootFile: ExternalFile get() = files().root

    context(runtime: ServerRuntime)
    private fun locallyServed(): KotlinxIoExternalFileSystem = files() as? KotlinxIoExternalFileSystem
        ?: throw BadRequestException("You can't reach files of this file system through this server.")

    /**
     * Rebuilds the full served URL the client requested, which is what the file system signed and is
     * therefore what it can validate.
     */
    context(runtime: ServerRuntime)
    private fun Request<*>.fileUrl(): String {
        val path =
            path.trailingSegments?.toString()?.removePrefix("/") ?: throw BadRequestException("No file to look up")
        val withQuery = if (queryParameters.isNotEmpty()) "$path?$queryParameters" else path
        return locallyServed().serveUrl.removeSuffix("/") + "/" + withQuery
    }

    /**
     * HEAD handler. Returns metadata headers for a file without a body.
     */
    public val fetchHead: HttpHandler<PathSpec0> = path.any.head bind HttpHandler {
        val url = it.fileUrl()
        // We use !! on the line below since the URL in question ALWAYS matches the file system.
        // If you ever see a NPE, logic itself has broken and the universe will cease to exist shortly.
        // ... or your file system implementation is broken and doesn't recognize its own root URL.
        val head = try {
            locallyServed().parseExternalUrl(url)!!.head()
        } catch (_: Exception) {
            throw BadRequestException("Invalid file URL")
        } ?: throw NotFoundException("No file $url found")

        HttpResponse(
            body = null,
            status = HttpStatus.NoContent,
            headers = HttpHeaders(
                HttpHeader.ContentType to head.type.toString(),
                HttpHeader.ContentLength to head.size.bytes.toString(),
                HttpHeader.AcceptRanges to "bytes"
            )
        )
    }

    /**
     * GET handler. Streams the file bytes.
     */
    public val fetch: HttpHandler<PathSpec0> = path.any.get bind HttpHandler { request ->
        val url = request.fileUrl()
        val file = try {
            locallyServed().parseExternalUrl(url)!!
        } catch (_: Exception) {
            throw BadRequestException("Invalid file URL")
        }

        var headCache: FileInfo? = null
        suspend fun head() =
            headCache ?: file.head()?.also { headCache = it } ?: throw BadRequestException("No file $url found")

        val ranges = request.headers.httpRanges(malformedRanges = malformedRanges)?.let { ranges ->
            if (ranges.isEmpty()) return@let null

            // RFC 9110 14.2: A server that supports range requests MAY ignore or reject a Range header field that contains [...] a ranges-specifier with more than two overlapping ranges,
            // or a set of many small ranges that are not listed in ascending order, since these are indications of either a broken client or a deliberate denial-of-service attack

            val size = head().size.bytes
            val sorted = ranges.sortedBy { it.rangeStart(size) }

            if (ranges.size > 10 && sorted != ranges) when (malformedRanges) {
                HttpRange.MalformedBehavior.IgnoreRangeRequest -> return@let null
                HttpRange.MalformedBehavior.BadRequest -> throw BadRequestException("Too many ranges provided not in ascending order")
            }

            if (ranges.size - sorted.mergeOverlaps(size).size > 2) when (malformedRanges) {
                HttpRange.MalformedBehavior.IgnoreRangeRequest -> return@let null
                HttpRange.MalformedBehavior.BadRequest -> throw BadRequestException("Too many overlapping ranges provided")
            }

            if (ranges.any { it.rangeStart(size) < 0 || it.rangeEnd(size) >= size }) return@HttpHandler HttpResponse(
                status = HttpStatus.RequestedRangeNotSatisfiable,
                headers = HttpHeaders(
                    HttpHeader.ContentRange to "bytes */$size"
                )
            )

            ranges
        }

        val content = file.get() ?: throw NotFoundException("No file $url found")

        when (ranges?.size) {
            null -> HttpResponse(content)

            1 -> {
                val range = ranges.first()
                val size = head().size.bytes

                HttpResponse(
                    status = HttpStatus.PartialContent,
                    headers = HttpHeaders(
                        HttpHeader.ContentRange to "bytes ${range.rangeStart(size)}-${range.rangeEnd(size)}/$size",
                        HttpHeader.AcceptRanges to "bytes",
                    ),
                    body = content.getRange(range, size)
                )
            }

            else -> {
                HttpResponse(
                    status = HttpStatus.PartialContent,
                    headers = HttpHeaders(
                        HttpHeader.AcceptRanges to "bytes",
                    ),
                    body = content.getRanges(ranges, head().size.bytes)
                )
            }
        }
    }

    /**
     * PUT handler. Accepts an upload to a signed upload URL understood by KotlinxIoExternalFileSystem.
     *
     * Only KotlinxIoExternalFileSystem supports server-generated upload URLs; other implementations will
     * reject uploads here.
     */
    public val upload: HttpHandler<PathSpec0> = path.any.put bind HttpHandler {
        val url = it.fileUrl()

        try {
            locallyServed().parseUploadUrl(url)!!.put(it.body!!)
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
