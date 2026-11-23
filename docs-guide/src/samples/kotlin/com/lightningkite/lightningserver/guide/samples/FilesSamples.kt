package com.lightningkite.lightningserver.guide.samples

// region files-imports
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.files.*
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.serialization.StandardWithExternalModule
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.MimeType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.validation.AnnotationValidators
import com.lightningkite.services.files.*
import kotlinx.serialization.Serializable
// endregion files-imports

// region files-server
@Serializable
data class SubmitDocument(
    val title: String,
    @MimeType("application/pdf", "image/*", maxSize = 10_000_000) val file: ServerFile,
)

object FileServer : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val files = setting("files", ExternalFileSystem.Settings())

    // Without this, @MimeType is ignored.
    override val annotationValidators: Runtime<AnnotationValidators> = Runtime.Cached {
        AnnotationValidators.StandardWithExternalModule() + AnnotationValidators.Files()
    }

    // Shared upload machinery: tokens, the ServerFile serializer, cleanup.  Include exactly one.
    val uploads = path.path("uploads") include FileUploadBasis(
        files = files,
        database = database,
        fileScanner = Runtime.Constant(listOf()),
    )

    // GET /documents/upload and POST /documents/upload/verify
    val documentUpload = path.path("documents").path("upload") include
        UploadEndpoints(uploads, "documents", noAuth)

    // Serves (and accepts PUTs to) the local file system's signed URLs.
    val served = path.path("files") include FileSystemEndpoints(files)

    val submit = path.path("documents").post bind ApiHttpHandler(
        summary = "Submit a document",
        auth = noAuth,
        implementation = { input: SubmitDocument -> input.file },
    )

    init {
        registerBasicMediaTypeCoders()
    }
}
// endregion files-server

// region files-direct
object ReportServer : ServerBuilder() {
    val files = setting("files", ExternalFileSystem.Settings())

    val writeReport = path.path("reports").post bind ApiHttpHandler(
        summary = "Write a report",
        auth = noAuth,
        implementation = { text: String ->
            val file = files().root.then("reports").thenRandom("report", "txt")
            file.put(TypedData.text(text, MediaType.Text.Plain))

            val info = file.head()        // FileInfo?: type, size, lastModified
            val back = file.get()?.text() // null if the file doesn't exist
            check(info != null && back == text)

            file.signedUrl                // a URL the client can GET
        },
    )
}
// endregion files-direct
