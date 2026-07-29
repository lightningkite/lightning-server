# File Systems

Last updated July 2025 (`version-5`)

Storing, serving, and using user-provided files is a common requirement, so Lightning Server provides a file-system
abstraction and a set of ready-made upload endpoints. The abstraction lives in the service-abstractions library
(`com.lightningkite.services.files`), and the HTTP endpoints that wrap it live in the `files` / `files-shared`
modules.

Available backends include a local (KotlinX-IO) file system and AWS S3. The API is modeled loosely on Kotlin's
built-in file functions.

## Declaring the need for a file system

Add a setting whose value is a `PublicFileSystem`:

```kotlin
import com.lightningkite.services.files.PublicFileSystem

object Server : ServerBuilder() {
    val files = setting("files", PublicFileSystem.Settings())
}
```

To make additional backends available, reference them in an `init` block so their loaders register themselves:

```kotlin
import com.lightningkite.services.files.s3.S3PublicFileSystem

object Server : ServerBuilder() {
    init { S3PublicFileSystem }
    val files = setting("files", PublicFileSystem.Settings())
}
```

Like every service, the setting resolves to a `Runtime<PublicFileSystem>`. You invoke it inside a
`ServerRuntime` context (for example, within a handler) to get the live `PublicFileSystem`.

## Accessing files

`FileObject` is the internal handle used to read and write files. Get one from the file system's `root` and
navigate with `then`:

```kotlin
import com.lightningkite.services.data.TypedData
import com.lightningkite.MediaType

val root = files().root

val testFile = root.then("some/path/file.txt")

// Files are written and read as TypedData. Parent folders are created implicitly.
testFile.put(TypedData.text("Hello world!", MediaType.Text.Plain))

// get() returns TypedData?, null if the file does not exist.
val text = testFile.get()?.text()

// Metadata only (media type, size) without downloading the body.
val info = testFile.head()

// Remove a file.
testFile.delete()
```

## Serving files

Files can be served over signed URLs. Performing an HTTP GET against a signed URL returns the file:

```kotlin
// The signature duration is determined by the file system settings.
println(testFile.signedUrl)
```

### `FileSystemEndpoints`

`FileSystemEndpoints` exposes HEAD/GET/PUT handlers for a `PublicFileSystem`, including HTTP Range support on GET
for partial/resumable downloads. GET streams the bytes, HEAD returns metadata, and PUT accepts an upload to a
signed upload URL (only the local `KotlinxIoPublicFileSystem` accepts server-generated uploads this way; S3/Azure
sign their own upload URLs).

```kotlin
val fileServing = path.path("files") include FileSystemEndpoints(files)
```

## Uploading files from a client

You can sign a PUT upload URL for any file reference:

```kotlin
import kotlin.time.Duration.Companion.minutes

val uploadUrl = root.then("uploads/photo.jpg").uploadUrl(10.minutes)
```

Performing an HTTP PUT of the file's contents to that URL writes the file. Upload URLs never grant read access.

## `ServerFile` and serialization

Two types work together:

- `FileObject` is the internal read/write handle. It never leaves the server.
- `ServerFile` (`com.lightningkite.services.files.ServerFile`) is a serializable wrapper around a URL string.
  It is what you store in models and send over your API.

```kotlin
import com.lightningkite.services.files.ServerFile

@GenerateDataClassPaths
@Serializable
data class Post(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    @MimeType("image/*") val coverImage: ServerFile? = null,
) : HasId<Uuid>
```

You move between the two by resolving the `ServerFile`'s location against the file system:

```kotlin
val obj = files().root.then(post.coverImage!!.location) // ServerFile -> FileObject
val ref = ServerFile(obj.url)                            // FileObject -> ServerFile
```

### Security

When a `ServerFile` is serialized out to a client, its URL is automatically signed for reading. Consequently, a
file stays private as long as you only serialize references to it for people you want to be able to read it. Do not
build a public file-sharing feature on top of this behavior by accident.

## The `UploadEarlyEndpoint` (recommended upload flow)

`UploadEarlyEndpoint` is an opinionated group of endpoints for the common "upload now, reference later" pattern. It
lets a client upload a file before the request that actually uses it, without turning your file system into an open
file-sharing service.

Construct it with the file system, a database (used to track pending uploads for garbage collection), and a list of
`FileScanner`s (often empty):

```kotlin
import com.lightningkite.lightningserver.files.UploadEarlyEndpoint
import com.lightningkite.lightningserver.definition.Runtime

object Server : ServerBuilder() {
    val files = setting("files", PublicFileSystem.Settings())
    val database = setting("database", Database.Settings())

    // Register ONCE. See the warning below.
    val uploadEarly = path.path("upload") module UploadEarlyEndpoint(
        files = files,
        database = database,
        fileScanner = Runtime.Constant(listOf()),
    )
}
```

### The flow

1. The client calls the `endpoint` (GET) to obtain an `UploadInformation`:
    - `uploadUrl` — a presigned PUT URL to upload the bytes to.
    - `futureCallToken` — a token that can be sent as a serialized `ServerFile` in a later request.
2. The client PUTs the file to `uploadUrl`.
3. The client includes `futureCallToken` as the `ServerFile` value in a subsequent API call.

Neither URL grants read access, so the endpoint cannot be abused as a file host. A daily `cleanupSchedule` deletes
uploads that were prepared but never used before their `expiration` (default one day).

It is strongly recommended that you use this endpoint for API file handling rather than implementing the flow
yourself.

### Quarantine (jail) and scanning

If you pass a non-empty list of `FileScanner`s (for example, the ClamAV scanner from the `files-clamav` module),
uploads are first written to a jailed location (`jailFilePath`, default `upload-jail`) instead of the ready
location (`filePath`, default `uploaded`). Before the
file can be used it must be scanned and moved out of jail. The client does this by calling the `verify` (POST)
endpoint with the token; if the file is safe it is moved to the ready location and a reusable reference is returned.
Verifying up front also makes the subsequent request faster, since the scan has already happened.

With an empty scanner list, uploads go straight to the ready location and are certified as already scanned.

### ⚠️ Only one `UploadEarlyEndpoint` per server

`UploadEarlyEndpoint` registers a **contextual serializer for `ServerFile`** (via its `externalSerialization`) so
that upload tokens can be decoded as `ServerFile` values. If you instantiate `UploadEarlyEndpoint` more than once
(for example, a separate instance created only for a test), the multiple instances register **conflicting
`ServerFile` serializers**. This does not fail at compile time — it surfaces at runtime as serialization errors,
typically appearing as `500 Internal Server Error` responses.

**Instantiate `UploadEarlyEndpoint` exactly once in your server definition and reference that single instance
everywhere, including from tests.**

## Available Backends

### Local

Use a local filesystem folder:

```json5
// settings.json
{
  "files": { "url": "file://path-to-folder" }
}
```

### S3

```kotlin
// Server.kt
object Server : ServerBuilder() {
    init { S3PublicFileSystem }
}
```

```json5
// settings.json
{
  "files": { "url": "s3://[user]:[password]@[bucket].[region].amazonaws.com" }
}
```
