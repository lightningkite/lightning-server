# Media

The `media` module records image metadata (size, dimensions) and generates previews (thumbnails, resized copies,
format conversions) for uploaded files.  Store a `ServerFileWithMetadata` instead of a bare `ServerFile` and wrap the
model's table so processing happens on every write.

```kotlin
// build.gradle.kts
implementation("com.lightningkite.lightningserver:media:$lightningServerVersion")
// Multiplatform client code that only reads the models:
implementation("com.lightningkite.lightningserver:media-shared:$lightningServerVersion")
```

`media` is JVM-only and uses [Scrimage](https://sksamuel.github.io/scrimage/).  It builds on [Files](files.md).

## Processing on write

<!-- sample: com/lightningkite/lightningserver/guide/samples/MediaSamples.kt#media-imports -->
```kotlin
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.files.FileUploadBasis
import com.lightningkite.lightningserver.media.*
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.typed.ModelRestEndpoints
import com.lightningkite.lightningserver.typed.modelInfo
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.Queryable
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.ModelPermissions
import com.lightningkite.services.files.ExternalFileSystem
import kotlin.uuid.Uuid
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/MediaSamples.kt#media-server -->
```kotlin
@Queryable
data class GalleryPhoto(
    override val _id: Uuid = Uuid.random(),
    val caption: String,
    val image: ServerFileWithMetadata? = null,
) : HasId<Uuid>

object GalleryServer : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val files = setting("files", ExternalFileSystem.Settings())

    // Resolving a ServerFile to storage needs the serializer an upload basis installs.
    val uploads = path.path("uploads") include FileUploadBasis(files, database, Runtime.Constant(listOf()))

    val photoInfo = database.modelInfo<HasId<*>?, GalleryPhoto, Uuid>(
        auth = noAuth,
        tableName = "GalleryPhoto",
        signals = { table ->
            table.interceptImagesForProcessing(
                MediaPreviewOptions(sizeInPixels = 200, type = MediaType.Image.JPEG),
                MediaPreviewOptions(sizeInPixels = 1200),
                makePath = { it.image },
            )
        },
        permissions = { ModelPermissions.allowAll() },
    )

    val photos = path.path("photos") include ModelRestEndpoints(photoInfo)

    init {
        registerBasicMediaTypeCoders()
    }
}
```

A client uploads the file as in [Files](files.md), then creates a `GalleryPhoto` with
`image = ServerFileWithMetadata(original = verifiedFile)`.  Before the insert is written, the interceptor downloads
the original, fills in `mimeType`, `size`, `width` and `height`, and writes one preview per option next to the
original.  An update that assigns `image` is processed the same way.

Non-image files only get `mimeType` and `size`.

Use `interceptImagesForProcessingNotNull` when the field is non-nullable.

## Processing in the background

Interceptors hold the request until every preview is written.  For large images, process after the write instead:

<!-- sample: com/lightningkite/lightningserver/guide/samples/MediaSamples.kt#media-background -->
```kotlin
object BackgroundGalleryServer : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val files = setting("files", ExternalFileSystem.Settings())
    val uploads = path.path("uploads") include FileUploadBasis(files, database, Runtime.Constant(listOf()))

    val photoInfo = database.modelInfo<HasId<*>?, GalleryPhoto, Uuid>(
        auth = noAuth,
        tableName = "GalleryPhoto",
        permissions = { ModelPermissions.allowAll() },
    )

    // Must be bound to a path, or it logs a warning and never runs.
    val processPhotos = path.path("process-photos") bind processImagesInBackground(
        photoInfo,
        MediaPreviewOptions(sizeInPixels = 200, type = MediaType.Image.JPEG),
        makePath = { it.image },
    )

    val photos = path.path("photos") include ModelRestEndpoints(photoInfo)
}
```

`processImagesInBackground` listens for changes through the `ModelInfo` and starts a [task](tasks.md) whenever the
field changes, then writes the result with `baseTable()`.  Only changes made through the `ModelInfo`'s tables are
seen.  The field must be nullable, and until the task finishes `previews` is empty.

| | `interceptImagesForProcessing` | `processImagesInBackground` |
|---|---|---|
| Previews ready when the write returns | Yes | No |
| Adds latency to the write | Yes, per image | No |
| A processing failure | Fails the write | Fails the task (default timeout 5 minutes) |

## Preview options

| `MediaPreviewOptions` parameter | Effect |
|---|---|
| `sizeInPixels` | Scale down until the shorter side is this size, so the longer side may exceed it (a 400×300 image at 200 becomes 266×200).  Images are never enlarged. |
| `forceRatio` | Crop or pad to `width / height` (e.g. `16.0 / 9.0`) before scaling. |
| `type` | Output format: `MediaType.Image.PNG`, `JPEG`, `WebP`, `Tiff`, `GIF` or `BMP`.  `null` keeps the original's. |
| `quality` | 0.0 to 1.0, used for JPEG (default 0.95). |

`MediaPreviewOptions.CorrectOddFeatures` only fixes EXIF orientation.  An option that would change nothing is
skipped, so no preview is written for it.  Other formats (AVIF, APNG) log a warning and are skipped.

Previews are stored beside the original as `<name>-<options>.<ext>`, e.g. `abc-200-jpg.jpg` for
`MediaPreviewOptions(sizeInPixels = 200, type = MediaType.Image.JPEG)`.

## Choosing a preview to show

<!-- sample: com/lightningkite/lightningserver/guide/samples/MediaSamples.kt#media-pick -->
```kotlin
fun thumbnailFor(photo: ServerFileWithMetadata) = photo
    .previews(setOf(MediaType.Image.JPEG, MediaType.Image.WebP), preferredMinimumWidth = 150)
    .firstOrNull()
    ?.file
    ?: photo.original
```

`previews(supportedTypes, preferredMinimumWidth, preferredMinimumHeight)` sorts the previews of those types,
preferring the smallest one at least the requested size.  It lives in `media-shared`, so clients can call it too.

## Gotchas

- The server must include a `FileUploadBasis` (or `GlobalUploadEndpoints`): processing resolves `original` through
  the `ServerFile` serializer it installs.
- Processing replaces `previews` each time it runs.  The old preview files are not deleted.
- The synchronous interceptor reprocesses on every write that assigns the field, even to the same value.
- `AnnotationValidators.Media` is deprecated and does nothing; validate files with `@MimeType` (see
  [Files](files.md#validating-file-type-and-size)).

## Next

- [Files](files.md): uploads and `ServerFile`.
- [Tasks](tasks.md): how background tasks run.
- [Model REST endpoints](model-rest.md): `ModelInfo` and its `signals` hook.
