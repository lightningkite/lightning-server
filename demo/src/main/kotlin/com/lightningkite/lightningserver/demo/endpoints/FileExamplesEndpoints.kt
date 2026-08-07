package com.lightningkite.lightningserver.demo.endpoints

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.arg1
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.route
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.files.ExternalFileSystem
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/**
 * FileExamplesEndpoints - Demonstrates file storage operations directly against
 * [ExternalFileSystem]: writing bytes, reading them back, generating signed URLs, and deleting.
 *
 * This is the low-level counterpart to [Server.uploadEarly] (`UploadEarlyEndpoint`), which
 * demonstrates the early-binding client-upload flow instead. Every operation here touches real
 * storage - nothing is fabricated - so [getFileInfo], [getSignedUrl], and [deleteFile] genuinely
 * 404 when the file doesn't exist, rather than always succeeding on a made-up path.
 */
class FileExamplesEndpoints(
    private val files: Runtime<ExternalFileSystem>,
) : ServerBuilder() {

    /**
     * POST /files/upload
     *
     * Writes [UploadFileRequest.content] as a real text file at uploads/{fileName} and returns
     * a genuine signed URL for it.
     */
    val uploadFile = path.path("files").path("upload").post bind ApiHttpHandler(
        summary = "Upload a file",
        description = "Writes the given text content to storage and returns a signed URL for it",
        auth = noAuth,
        errorCases = listOf(
            LSError(http = 400, detail = "no-file", message = "File name is required")
        ),
        successCode = HttpStatus.Created,
        implementation = { input: UploadFileRequest ->
            if (input.fileName.isBlank()) {
                throw BadRequestException("File name is required")
            }

            val file = files().root.then("uploads", input.fileName)
            val data = TypedData.text(input.content, MediaType.Text.Plain)
            file.put(data)

            UploadFileResponse(
                signedUrl = file.signedUrl,
                fileName = input.fileName,
                fileSize = input.content.encodeToByteArray().size.toLong(),
            )
        }
    )

    /**
     * GET /files/{path}/info
     *
     * Reads real metadata (media type, size, last modified) for a stored file via `head()`.
     */
    val getFileInfo = path.path("files").arg<String>("path").path("info").get bind ApiHttpHandler(
        summary = "Get file information",
        description = "Retrieves metadata about a stored file",
        auth = noAuth,
        errorCases = listOf(
            LSError(http = 404, detail = "not-found", message = "File not found")
        ),
        successCode = HttpStatus.OK,
        implementation = { _: Unit ->
            val filePath = route.arg1
            val file = files().root.then("uploads", filePath)
            val info = file.head() ?: throw NotFoundException("File not found")

            FileInfoResponse(
                path = filePath,
                mediaType = info.type.toString(),
                sizeBytes = info.size.bytes,
                signedUrl = file.signedUrl,
            )
        }
    )

    /**
     * GET /files/{path}/signed-url
     *
     * Generates a signed URL for temporary access, after confirming the file actually exists.
     */
    val getSignedUrl = path.path("files").arg<String>("path").path("signed-url").get bind ApiHttpHandler(
        summary = "Generate a signed URL for file access",
        description = "Creates a temporary signed URL that allows access to a private file",
        auth = noAuth,
        errorCases = listOf(
            LSError(http = 404, detail = "not-found", message = "File not found")
        ),
        successCode = HttpStatus.OK,
        implementation = { _: Unit ->
            val filePath = route.arg1
            val file = files().root.then("uploads", filePath)
            if (file.head() == null) throw NotFoundException("File not found")

            SignedUrlResponse(
                url = file.signedUrl,
                path = filePath,
            )
        }
    )

    /**
     * DELETE /files/{path}
     *
     * Deletes a file from storage, after confirming it exists.
     */
    val deleteFile = path.path("files").arg<String>("path").delete bind ApiHttpHandler<_, Nothing?, Unit, Unit>(
        summary = "Delete a file",
        description = "Removes a file from the storage system",
        auth = noAuth,
        errorCases = listOf(
            LSError(http = 404, detail = "not-found", message = "File not found")
        ),
        successCode = HttpStatus.NoContent,
        implementation = { _: Unit ->
            val filePath = route.arg1
            val file = files().root.then("uploads", filePath)
            if (file.head() == null) throw NotFoundException("File not found")
            file.delete()
        }
    )

    /**
     * POST /files/upload-image
     *
     * Uploads a base64-encoded image with type/size validation, storing real bytes at
     * images/{fileName}.
     */
    val uploadImage = path.path("files").path("upload-image").post bind ApiHttpHandler(
        summary = "Upload an image file",
        description = "Uploads a base64-encoded image with validation for image types (JPEG, PNG, GIF, WebP)",
        auth = noAuth,
        errorCases = listOf(
            LSError(http = 400, detail = "invalid-type", message = "File must be an image (JPEG, PNG, GIF, or WebP)"),
            LSError(http = 400, detail = "file-too-large", message = "Image file size exceeds maximum allowed size")
        ),
        successCode = HttpStatus.Created,
        implementation = { input: UploadImageRequest ->
            val allowedTypes = listOf("image/jpeg", "image/png", "image/gif", "image/webp")
            if (input.mimeType !in allowedTypes) {
                throw BadRequestException("File must be an image (JPEG, PNG, GIF, or WebP)")
            }

            val bytes = Base64.decode(input.contentBase64)

            val maxSize = 10 * 1024 * 1024
            if (bytes.size > maxSize) {
                throw BadRequestException("Image file size exceeds maximum allowed size")
            }

            val file = files().root.then("images", input.fileName)
            file.put(TypedData.bytes(bytes, MediaType(input.mimeType)))

            UploadImageResponse(
                signedUrl = file.signedUrl,
                fileSize = bytes.size.toLong(),
            )
        }
    )
}

// Request/Response models

@Serializable
data class UploadFileRequest(
    val fileName: String,
    val content: String,
)

@Serializable
data class UploadFileResponse(
    val signedUrl: String,
    val fileName: String,
    val fileSize: Long,
)

@Serializable
data class FileInfoResponse(
    val path: String,
    val mediaType: String,
    val sizeBytes: Long,
    val signedUrl: String,
)

@Serializable
data class SignedUrlResponse(
    val url: String,
    val path: String,
)

@Serializable
data class UploadImageRequest(
    val fileName: String,
    val contentBase64: String,
    val mimeType: String,
)

@Serializable
data class UploadImageResponse(
    val signedUrl: String,
    val fileSize: Long,
)
