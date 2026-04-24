package com.lightningkite.lightningserver.demo.endpoints

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.arg1
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.route
import com.lightningkite.services.database.Database
import com.lightningkite.services.files.PublicFileSystem
import com.lightningkite.services.files.ServerFile
import kotlinx.serialization.Serializable

/**
 * FileExamplesEndpoints - Demonstrates file handling operations.
 *
 * These examples showcase:
 * - File uploads (early and late binding)
 * - File downloads
 * - Signed URLs for temporary access
 * - File metadata and storage
 * - Integration with different storage backends (S3, local, etc.)
 */
class FileExamplesEndpoints(
    private val files: Runtime<PublicFileSystem>,
    private val database: Runtime<Database>,
) : ServerBuilder() {

    /**
     * POST /files/upload
     * 
     * Upload a file and store it in the file system.
     * Demonstrates: File upload, ServerFile handling, file metadata
     */
    val uploadFile = path.path("files").path("upload").post bind ApiHttpHandler(
        summary = "Upload a file",
        description = "Uploads a file to the server and returns file information including a signed URL",
        auth = noAuth,
        errorCases = listOf(
            LSError(http = 400, detail = "no-file", message = "No file provided in request")
        ),
        successCode = HttpStatus.Created,
        implementation = { input: UploadFileRequest ->
            // In a real implementation, you would extract the file from the request body
            // For this example, we'll simulate file upload
            if (input.fileName.isBlank()) {
                throw BadRequestException("File name is required")
            }

            // Create a ServerFile reference
            // In practice, this would be created from the actual uploaded file
            val serverFile = ServerFile(
                location = "uploads/${input.fileName}"
            )

            val fileSystem = files.await()
            UploadFileResponse(
                file = serverFile,
                signedUrl = fileSystem.root.then(serverFile.location).signedUrl,
                fileName = input.fileName,
                fileSize = input.fileSize
            )
        }
    )

    /**
     * GET /files/{path}/info
     * 
     * Get information about a file.
     * Demonstrates: File metadata retrieval
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
            val fileSystem = files.await()
            val fileRef = fileSystem.root.then(filePath)

            FileInfoResponse(
                path = filePath,
                signedUrl = fileRef.signedUrl,
                expiresIn = "24 hours"
            )
        }
    )

    /**
     * GET /files/{path}/signed-url
     * 
     * Generate a signed URL for temporary file access.
     * Demonstrates: Signed URL generation with expiration
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
            val fileSystem = files.await()
            val fileRef = fileSystem.root.then(filePath)

            SignedUrlResponse(
                url = fileRef.signedUrl,
                expiresIn = "24 hours",
                path = filePath
            )
        }
    )

    /**
     * DELETE /files/{path}
     * 
     * Delete a file from storage.
     * Demonstrates: File deletion
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
            val fileSystem = files.await()
            val fileRef = fileSystem.root.then(filePath)

            // Delete the file
            fileRef.delete()
        }
    )

    /**
     * POST /files/upload-image
     * 
     * Upload an image with validation.
     * Demonstrates: File type validation, image-specific handling
     */
    val uploadImage = path.path("files").path("upload-image").post bind ApiHttpHandler(
        summary = "Upload an image file",
        description = "Uploads an image file with validation for image types (JPEG, PNG, GIF, WebP)",
        auth = noAuth,
        errorCases = listOf(
            LSError(http = 400, detail = "invalid-type", message = "File must be an image (JPEG, PNG, GIF, or WebP)"),
            LSError(http = 400, detail = "file-too-large", message = "Image file size exceeds maximum allowed size")
        ),
        successCode = HttpStatus.Created,
        implementation = { input: UploadImageRequest ->
            // Validate file type
            val allowedTypes = listOf("image/jpeg", "image/png", "image/gif", "image/webp")
            if (input.mimeType !in allowedTypes) {
                throw BadRequestException(
                    "File must be an image (JPEG, PNG, GIF, or WebP)"
                )
            }

            // Validate file size (10MB limit)
            val maxSize = 10 * 1024 * 1024L
            if (input.fileSize > maxSize) {
                throw BadRequestException(
                    "Image file size exceeds maximum allowed size"
                )
            }

            val serverFile = ServerFile(
                location = "images/${input.fileName}"
            )

            val fileSystem = files.await()
            UploadImageResponse(
                file = serverFile,
                signedUrl = fileSystem.root.then(serverFile.location).signedUrl,
                thumbnailUrl = fileSystem.root.then("thumbnails/${input.fileName}").signedUrl
            )
        }
    )
}

// Request/Response models

@Serializable
data class UploadFileRequest(
    val fileName: String,
    val fileSize: Long = 0,
)

@Serializable
data class UploadFileResponse(
    val file: ServerFile,
    val signedUrl: String,
    val fileName: String,
    val fileSize: Long,
)

@Serializable
data class FileInfoResponse(
    val path: String,
    val signedUrl: String,
    val expiresIn: String,
)

@Serializable
data class SignedUrlResponse(
    val url: String,
    val expiresIn: String,
    val path: String,
)

@Serializable
data class UploadImageRequest(
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
)

@Serializable
data class UploadImageResponse(
    val file: ServerFile,
    val signedUrl: String,
    val thumbnailUrl: String,
)
