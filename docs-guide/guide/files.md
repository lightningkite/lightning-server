# Files

User-supplied files (avatars, documents, attachments) live in an `ExternalFileSystem`: a local folder in
development, S3 or Azure Blob in production.  The `files` module adds the HTTP side: endpoints that let a client
upload a file before the request that uses it, and endpoints that serve a local file system's signed URLs.

```kotlin
// build.gradle.kts
implementation("com.lightningkite.lightningserver:files:$lightningServerVersion")
```

## A complete upload flow

<!-- sample: com/lightningkite/lightningserver/guide/samples/FilesSamples.kt#files-imports -->
```kotlin
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
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/FilesSamples.kt#files-server -->
```kotlin
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
```

A client sends a file in four steps:

1. `GET /documents/upload` returns an `UploadTarget`: an `uploadUrl` and a `token` (sent as `futureCallToken`).
2. PUT the bytes to `uploadUrl`.  The URL can only write that one file; it grants no read access.
3. `POST /documents/upload/verify` with the token.  The server scans the file (if scanners are configured), moves it
   to the ready folder, and returns a `ServerFile`.
4. Send that `ServerFile` wherever a request expects one, e.g. `SubmitDocument.file`.

The generated Kotlin SDK exposes each `UploadEndpoints` as a `ClientFileUploadEndpoints` with `getFileUploadUrl()` and
`verifyUpload(token)`.  Always call `verifyUpload`, even on servers without scanners, where it is a cheap no-op.

When the request is decoded, the token is checked (signature, expiry, scanned) and claimed, so the cleanup task
won't delete the file.  Uploads never claimed are deleted by `FileUploadBasis.cleanupSchedule` (daily) once their
`expiration` (default one day) passes.

## `ServerFile` and `ExternalFile`

| Type | Where it lives | What it is |
|---|---|---|
| `ServerFile` | Models, request and response bodies | A serializable reference.  Stored as `sf://<file system>/<path>`. |
| `ExternalFile` | Server code only | A handle you read and write through. |

Convert with `externalFile.serverFile` and `serverFile.externalFile`.  `serverFile.externalFile` needs the contextual
`ServerFile` serializer that `FileUploadBasis` installs, so a server that resolves stored files must include one.

On the way out, the serializer turns every `ServerFile` into a signed URL the client can GET.  On the way in, it
accepts only upload tokens and signed URLs from your own file systems.  So a file is readable by exactly the people
you serialize it to; never serialize a reference to someone who shouldn't read it.

## Reading and writing files directly

<!-- sample: com/lightningkite/lightningserver/guide/samples/FilesSamples.kt#files-direct -->
```kotlin
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
```

Other `ExternalFile` operations: `delete()`, `list()`, `copyTo(other)`, `moveTo(other)`, `getRange(range)`,
`signUrl(timeout)`, `uploadUrl(timeout)`, `parent`, `name`, `extension`.  `then("a/b")` splits on `/` and rejects
`.` and `..` segments.

## Per-feature upload folders and permissions

Declare one `UploadEndpoints` per feature that accepts uploads, each with its own folder and `AuthRequirement`.
They all share the one `FileUploadBasis`.  Here `UserAuth` stands for your user type's auth (see [Auth](auth.md)):

```kotlin
val avatarUpload = path.path("users").path("avatar-upload") include
    UploadEndpoints(uploads, UserAuth.require()) { "users/${auth.rawId}/avatars" }
```

The folder lambda runs on every upload and must return `""` or a relative path with no empty, `.` or `..` segments;
anything else fails the upload.  Never build it from client input.

`GlobalUploadEndpoints(files, database, fileScanner)` is the older single-endpoint form: a basis plus one
`UploadEndpoints` at the root folder, with `authOptions` defaulting to `noAuth`.  `UploadEarlyEndpoint` is a
deprecated alias for it.

## Scanning uploads

Pass scanners to `FileUploadBasis.fileScanner` and every upload lands in a jail folder until `verify` scans it:

```kotlin
val scanner = setting("scanner", FileScanner.Settings("mime"))

val uploads = path.path("uploads") include FileUploadBasis(
    files = files,
    database = database,
    fileScanner = Runtime { listOf(scanner()) },
)
```

| Scanner | URL | Module (Service Abstractions) |
|---|---|---|
| `CheckMimeFileScanner`: file signature matches its media type | `mime` | `files` |
| `ClamAvFileScanner` | `clamav://host[:port]/UNIX\|WINDOWS[?timeoutSeconds=30]` | `files-clamav` (reference `ClamAvFileScanner` in `init`) |

With scanners configured:

- `verify` is required.  A token for an unscanned upload is rejected when a request uses it.
- A file the scanner rejects fails `verify` with 400 `file-scan-failed`.
- The file system must sign its URLs (`signedUrlDuration` not `forever`), or startup fails: an unsigned backend
  would let a client name the jail file directly.
- Turning scanning on is not retroactive: tokens issued before stay usable until they expire.

## Validating file type and size

`@MimeType(vararg whitelist, blacklist = [], maxSize = bytes)` on a `ServerFile` property checks the stored file's
`head()` when a typed endpoint decodes the input.  It only runs if the server's `annotationValidators` include
`AnnotationValidators.Files()`, as in the first example.  See [Validation](validation.md).

## Backends

`ExternalFileSystem.Settings` is one URL.  Change it in settings to switch backends.

| URL | Backend |
|---|---|
| `file://local/files?serveUrl=files` (default) | Local folder.  `serveUrl` is required: relative values resolve against `general.publicUrl`; mount `FileSystemEndpoints` there. |
| `s3://bucket.s3-us-east-1.amazonaws.com` | S3 with the default credential chain.  Reference `S3ExternalFileSystem` in `init` (module `files-s3`). |
| `s3://profile@bucket.s3-us-east-1.amazonaws.com` | S3 with a named AWS profile |
| `s3://KEY:SECRET@bucket.s3-us-east-1.amazonaws.com` | S3 with static keys |

Both backends take `signedUrlDuration`: an ISO-8601 duration (`PT30M`), seconds (`1800`), or `forever` / `null` for
unsigned URLs.  The default is one hour.  Unsigned S3 URLs need a publicly readable bucket.

```json
{
  "files": "s3://my-app-files.s3-us-east-1.amazonaws.com?signedUrlDuration=PT1H"
}
```

`FileSystemEndpoints` only fronts the local backend: S3 serves its own signed URLs and accepts uploads directly.  Its
GET supports single HTTP `Range` requests (`Accept-Ranges: bytes`); pass
`malformedRanges = HttpRange.MalformedBehavior.BadRequest` to reject bad ranges instead of ignoring them.

## Gotchas

- Include exactly one `FileUploadBasis` (or `GlobalUploadEndpoints`) per server.  Each installs the contextual
  `ServerFile` serializer; two conflict and surface as 500s at runtime.  Tests should use the server's own instance.
- Never expose the `UploadForNextRequest` table through REST or a model endpoint: its rows name unscanned jail
  files.
- Upload URLs and tokens expire (default one day).  Upload and verify promptly.
- Store `ServerFile`, never a signed URL: signed URLs expire.

## Testing

Point the local backend at a scratch folder and its `serveUrl` at where `FileSystemEndpoints` is mounted:

```kotlin
FileServer.testBlocking(settings = {
    files set ExternalFileSystem.Settings("file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost:8080/files")
    database set Database.Settings()
}) {
    val target = FileServer.documentUpload.upload.test(null, Unit)
    // ...
}
```

The guide's [FilesTest](https://github.com/lightningkite/lightning-server/blob/master/docs-guide/src/test/kotlin/com/lightningkite/lightningserver/guide/FilesTest.kt)
runs the whole upload, PUT, verify and submit flow.

## Next

- [Media](media.md): thumbnails and image metadata for uploaded files.
- [Validation](validation.md): annotation validators.
- [Settings](settings.md): where the `files` URL comes from.
- [Services](services.md): registering backends such as S3.
