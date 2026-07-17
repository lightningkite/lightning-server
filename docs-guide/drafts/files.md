> # ⚠️ UNREVIEWED FIRST DRAFT — DO NOT PUBLISH
> Auto-generated first pass. Code samples are modeled on the current source but have **NOT** been compiled, drift-checked, or reviewed. This page is intentionally **not** in the site nav. Before publishing: port samples into the drift-checked `docs-guide/src/samples` module, run the fresh-eyes judge, and delete this banner.

# Files & Uploads

Lightning Server provides a unified abstraction for file storage through `PublicFileSystem`.  Files are
addressed server-side as `FileObject` values and serialized to clients as `ServerFile` URLs.  The
`UploadEarlyEndpoint` handles the standard upload workflow where a client uploads a file before
referencing it in a subsequent API call.

## Imports

All examples in this chapter use the following imports:

```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.files.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import com.lightningkite.services.files.*
import kotlinx.serialization.*
import kotlin.uuid.*
```

## Core Types

**`ServerFile`** is a thin value class wrapping a URL string.  It is what your models store and what
APIs serialize to and from clients.  When the framework serializes a `ServerFile` outbound, the
contextual serializer converts it to a signed URL the client can use directly.

**`FileObject`** is the server-side handle for performing I/O on a file.  It exposes `put()`,
`get()`, `head()`, `delete()`, `list()`, `uploadUrl()`, and navigation via `then()`.  `FileObject`
values are never sent to clients.

Converting between the two:

- `fileObject.serverFile` — wraps the `FileObject`'s internal URL in a `ServerFile`
  (available everywhere; no runtime needed).
- `serverFile.fileObject` — resolves a `ServerFile` back to a `FileObject` using the contextual
  serializer registered by `UploadEarlyEndpoint`.  Requires a `ServerRuntime` context; throws
  `IllegalStateException` if no serializer is configured.

## Declaring the Files Setting

Add `val files = setting("files", PublicFileSystem.Settings())` to your `ServerBuilder`.  The
default URL writes to a local `local/files` directory and serves files from a path relative to the
server's public URL:

```kotlin
object FileServer : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val files    = setting("files", PublicFileSystem.Settings())

    // Serve files at /files/**
    val served = path.path("files") include FileSystemEndpoints(files)

    // Upload-early endpoint at /upload (and /upload/verify)
    val uploadEarly = path.path("upload") include UploadEarlyEndpoint(
        files       = files,
        database    = database,
        fileScanner = { listOf() },  // no virus scanning; see "File Scanning" below
    )
}
```

> **Singleton rule:** Instantiate `UploadEarlyEndpoint` exactly **once** per server.  The endpoint
> registers a contextual serializer for `ServerFile`; a second instance registers a conflicting
> serializer and causes runtime serialization errors (500 responses) that are difficult to trace.
> See also: CLAUDE.md testing pitfalls.

`UploadEarlyEndpoint` constructor parameters (all optional beyond `files` and `database`):

| Parameter | Default | Purpose |
|---|---|---|
| `fileScanner` | — | `Runtime<List<FileScanner>>` — scanners run before files leave jail |
| `jailFilePath` | `"upload-jail"` | subdirectory for freshly-uploaded, unscanned files |
| `filePath` | `"uploaded"` | subdirectory for scanned, ready-to-use files |
| `expiration` | `1.days` | how long upload tokens remain valid |
| `authOptions` | `noAuth` | require authentication on the upload endpoint |

## The Upload-Early Flow

The standard upload workflow has three steps:

1. **Prepare** — client calls `GET /upload` and receives `UploadInformation`:

   ```json
   {
     "uploadUrl": "http://localhost:8080/files/uploaded/abc123.file?sig=…",
     "futureCallToken": "future-prescanned:abc123.file?useUntil=…&token=…"
   }
   ```

2. **Upload** — client HTTP-PUTs the file bytes to `uploadUrl`.

3. **Use** — client supplies `futureCallToken` wherever a `ServerFile` field is expected in a
   subsequent API request.  The contextual serializer validates the token, deletes the tracking
   record, and resolves a clean internal URL.

An optional step between 2 and 3: `POST /upload/verify` with the `futureCallToken` string as the
request body.  This scans and moves the file immediately so that subsequent deserialization is
faster.  It is a no-op if no file scanners are configured.

```
Client                         Server
  |                               |
  |--- GET /upload -------------->|
  |<-- {uploadUrl, futureCall...} |
  |                               |
  |--- PUT uploadUrl (bytes) ---->|  (direct to storage)
  |<-- 204 No Content             |
  |                               |
  | (optional)                    |
  |--- POST /upload/verify ------>|
  |<-- verified token             |
  |                               |
  |--- POST /api/posts            |
  |    { "image": futureCallToken }
  |<-- 201 Created                |
```

## FileObject Operations

Inside a handler, resolve a `FileObject` from the file system root:

```kotlin
// Illustrative — not a drift-checked sample region.
val exampleHandler = path.path("example").get bind HttpHandler {
    val file = files().root.then("uploads/photo.jpg")

    // Write
    // file.put(TypedData.source(bodySource, MediaType.Image.JPEG))

    // Read (null if the file does not exist)
    val content: TypedData? = file.get()

    // Metadata only — no download
    val info: FileInfo? = file.head()   // has .type, .size, .lastModified

    // Generate a unique filename under the same directory
    val random = file.parent!!.thenRandom("upload", "jpg")
    // → uploads/upload_<uuid>.jpg

    // Signed URL for client access
    val clientUrl: String = file.signedUrl

    HttpResponse.plainText(clientUrl)
}
```

`FileObject.then(path)` navigates to a sub-path.  `FileObject.thenRandom(prefix, extension)`
produces `{prefix}_{uuid}.{extension}`.

## Storing a ServerFile on a Model

Store `ServerFile` directly in your model:

```kotlin
// Illustrative — not a drift-checked sample region.
@Serializable
@GenerateDataClassPaths
data class Post(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val image: ServerFile,
) : HasId<Uuid>
```

When `Post` is returned by an endpoint within the same server that contains `UploadEarlyEndpoint`,
`image` is automatically signed before it reaches the client.  When a client submits a `Post` with
`image` set to a `futureCallToken`, the serializer validates, resolves, and cleans up the token.

## Configuring the Backend

`PublicFileSystem.Settings` holds a single URL string.  Change it in `settings.json` to switch
backends — no Kotlin changes are required.

### Local Filesystem

The default `settings.json` entry looks like:

```json
{
  "files": "file:///path/to/local/files?serveUrl=files"
}
```

Key points:

- `serveUrl` is **required** for the local backend.  A relative value (`serveUrl=files`) is resolved
  against the server's public URL (`https://api.example.com/files/`).  An absolute URL
  (`serveUrl=https://cdn.example.com/files`) is used verbatim.
- File uploads via `FileSystemEndpoints` require `KotlinxIoPublicFileSystem` (the local backend).
  Other backends reject server-generated upload URLs.
- Signed URL expiration defaults to 1 hour.  Use `&signedUrlDuration=forever` to disable expiration
  or `&signedUrlDuration=PT30M` for 30 minutes (any ISO 8601 duration or a number of seconds).

### AWS S3

Register the S3 backend before settings load, otherwise the `s3://` URL scheme is unrecognised at
startup (see [Services & Settings](services.md) for why this matters):

```kotlin
// Illustrative — add to your ServerBuilder init block.
object Server : ServerBuilder() {
    val files = setting("files", PublicFileSystem.Settings())

    init {
        S3PublicFileSystem   // registers the "s3://" scheme
    }
}
```

Then in `settings.json`:

```json
{
  "files": "s3://my-bucket.s3-us-east-1.amazonaws.com"
}
```

URL variants:

| Credential source | URL pattern |
|---|---|
| Default chain (IAM role, env vars, …) | `s3://bucket.s3-region.amazonaws.com` |
| Named AWS profile | `s3://profile-name@bucket.s3-region.amazonaws.com` |
| Static access key + secret | `s3://AKID:secretKey@bucket.s3-region.amazonaws.com` |

Append `?signedUrlDuration=1h` (or any ISO 8601 duration or seconds) to enable signed URLs.
Without this parameter URLs are unsigned; the bucket must be configured for public read access.

> **Note:** These URL examples are illustrative.  Verify exact formatting against the
> `S3PublicFileSystem` source in `service-abstractions/files-s3`.

## File Scanning

The `fileScanner` parameter accepts a `Runtime<List<FileScanner>>` — a lambda that produces the list
of scanners from the current runtime.  Pass `{ listOf() }` when no scanning is needed.

When scanners are present, freshly-uploaded files are written to the jail directory and must pass
scanning before `futureCallToken` is honoured.  The optional `/upload/verify` POST performs the
scan eagerly; otherwise the scan runs during deserialization when the token is submitted.

A ClamAV integration is available in the `files-clamav` module as `ClamAvFileScanner`.

## Testing

Override the `files` setting with a local temp directory and a `serveUrl` that matches the
`FileSystemEndpoints` mount point:

```kotlin
// Illustrative — not a drift-checked sample region.
fun fileUploadTest() = FileServer.testBlocking(
    settings = {
        files    set PublicFileSystem.Settings(
            "file://build/testfiles/${Uuid.random()}?serveUrl=http://localhost:8080/files"
        )
        database set Database.Settings()
    }
) {
    // Step 1: prepare
    val prepare = FileServer.uploadEarly.endpoint.test(null, Unit)
    // prepare.uploadUrl        → PUT bytes here (via FileSystemEndpoints.upload)
    // prepare.futureCallToken  → include as the ServerFile value in the next request
}
```

The `"ram"` database backend (the default) is sufficient for tracking upload records in tests; no
external database is needed.

## What's Next

- **Media processing** — add thumbnail generation and format conversion on top of `ServerFile`
  fields using `ServerFileWithMetadata` and `interceptImagesForProcessing`.  See
  [Media Processing](media.md).
- **Validation** — annotate `ServerFile` fields with `@MimeType(…)` and register
  `AnnotationValidators.Files` to enforce file type and size limits at the model layer.
- **Cleanup** — `UploadEarlyEndpoint` includes a built-in `cleanupSchedule` that runs daily and
  removes expired upload records and their associated files.
