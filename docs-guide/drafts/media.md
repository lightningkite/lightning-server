> # ⚠️ UNREVIEWED FIRST DRAFT — DO NOT PUBLISH
> Auto-generated first pass. Code samples are modeled on the current source but have **NOT** been compiled, drift-checked, or reviewed. This page is intentionally **not** in the site nav. Before publishing: port samples into the drift-checked `docs-guide/src/samples` module, run the fresh-eyes judge, and delete this banner.

# Media Processing

The `media` module generates preview variants — thumbnails, resized copies, format conversions — for image files
uploaded through the files module.  It plugs directly into the database interceptor and background-task APIs, so
preview generation is wired up in one or two lines on your model's table.

## Imports

All examples in this chapter use:

```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.media.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.database.*
import com.lightningkite.services.files.*
import com.lightningkite.services.data.MediaType
import kotlin.time.Duration.Companion.minutes
```

> The `media` module is JVM-only.  The shared models (`ServerFileWithMetadata`,
> `ServerFileWithMetadataPreview`) live in `media-shared` and are available on
> all Kotlin Multiplatform targets, so client code can read the metadata even on
> non-JVM platforms.

## The Core Type: `ServerFileWithMetadata`

Instead of storing a bare `ServerFile` on your model, use `ServerFileWithMetadata`.
It wraps the original reference together with lazily populated metadata and a list
of generated preview variants:

```kotlin
@Serializable
@GenerateDataClassPaths
data class ServerFileWithMetadata(
    val original: ServerFile,
    val mimeType: MediaType? = null,
    val size: Long? = null,
    val width: Int? = null,       // pixels; null for non-image files
    val height: Int? = null,      // pixels; null for non-image files
    val previews: List<ServerFileWithMetadataPreview> = listOf(),
)

@Serializable
@GenerateDataClassPaths
data class ServerFileWithMetadataPreview(
    val file: ServerFile,
    val mimeType: MediaType,
    val size: Long,
    val width: Int? = null,
    val height: Int? = null,
)
```

Use it in your model the same way you would use `ServerFile`:

```kotlin
@Serializable
@GenerateDataClassPaths
data class Product(
    override val _id: Uuid = Uuid.random(),
    val name: String,
    val photo: ServerFileWithMetadata,   // replaces bare ServerFile
) : HasId<Uuid>
```

> These declarations are taken verbatim from
> `media-shared/src/commonMain/kotlin/…/media/models.kt`.

## Configuring Preview Options

`MediaPreviewOptions` describes one output variant.  Pass as many as you need
to the processing functions below.

```kotlin
// Thumbnail — shrink so neither dimension exceeds 200 px
MediaPreviewOptions(sizeInPixels = 200)

// Web display — shrink to 1200 px, convert to JPEG, 85 % quality
MediaPreviewOptions(
    sizeInPixels = 1200,
    type = MediaType.Image.JPEG,
    quality = 0.85,
)

// Force a 16∶9 crop/pad, then shrink
MediaPreviewOptions(
    sizeInPixels = 800,
    forceRatio = 16.0 / 9.0,
)

// Only correct EXIF orientation — no resize, no conversion
MediaPreviewOptions.CorrectOddFeatures
```

| Parameter | Type | Default | Effect |
|-----------|------|---------|--------|
| `sizeInPixels` | `Int?` | `null` | Proportionally scale down so neither dimension exceeds this value.  Has no effect if the image is already smaller. |
| `forceRatio` | `Double?` | `null` | Crop or pad to the given `width / height` ratio before scaling. |
| `type` | `MediaType?` | `null` | Convert to this format.  `null` preserves the original. |
| `quality` | `Double?` | `null` | Lossy-compression quality, 0.0–1.0.  Applies mainly to JPEG (defaults to 0.95 when `null` and format is JPEG). |
| `destination` | `File?` | `null` | Write to this file; if `null` a temp file is created automatically. |

**Supported output formats:** PNG, JPEG, WebP, TIFF, GIF, BMP.
**Unsupported:** APNG, AVIF — options requesting these formats are silently skipped with a log warning.

Processing is powered by the [Scrimage](https://sksamuel.github.io/scrimage/) library (pulled in automatically by
the `media` Gradle module).

## Wiring Up Processing

There are two strategies.  Choose based on whether previews must be ready
*before* the create/update response is sent back.

### Strategy 1 — Synchronous Interceptors

`Table<T>.interceptImagesForProcessing` wraps the table so that every `insertOne`
and every modification that touches the image field triggers processing *before*
the operation completes.  The API response is held until previews are written.

```kotlin
context(runtime: ServerRuntime)
object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())

    val products = database()
        .table<Product>()
        .interceptImagesForProcessingNotNull(
            MediaPreviewOptions(sizeInPixels = 200),   // thumbnail
            MediaPreviewOptions(sizeInPixels = 1200),  // full-size web
            makePath = { it.path { it.photo } }
        )
}
```

Use `interceptImagesForProcessing` (with the trailing-`NotNull` dropped) when
the field is nullable (`ServerFileWithMetadata?`).

**When to use this strategy:**
- Previews must exist the moment the record is readable by clients.
- Images are small enough that processing doesn't noticeably delay the response.

### Strategy 2 — Background Task

`processImagesInBackground` returns a `Task<T>` that listens for changes on the
model and schedules processing work outside the request lifecycle.

```kotlin
context(runtime: ServerRuntime)
object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())

    // The task must be bound to a path or it will never execute.
    val processProductImages =
        path.path("tasks").path("process-product-images").task bind
            processImagesInBackground(
                info = productInfo,                             // ModelInfo<USER, Product, Uuid>
                MediaPreviewOptions(sizeInPixels = 200),
                MediaPreviewOptions(sizeInPixels = 1200),
                timeout = 5.minutes,
                makePath = { it.path { it.photo } }
            )
}
```

> **Important:** If you skip the `path.task bind` binding, the task object is
> never registered and the framework logs a warning:
> `"processImagesInBackground is unregistered and cannot be executed."`

**When to use this strategy:**
- Images may be large (processing time is unpredictable).
- Fast API responses are more important than immediate preview availability.
- Clients can tolerate a brief window where `previews` is empty.

### Comparing the Two Strategies

| | Synchronous interceptor | Background task |
|--|--|--|
| Previews available immediately | Yes | No — small delay |
| Impact on API latency | Proportional to image size | None |
| Failure handling | Propagates as an error | Logged; original record saved |
| Setup lines | One `.interceptImages…(…)` call | Task binding + `path.task bind` |

## Selecting the Best Preview at Read Time

`ServerFileWithMetadata.previews(supportedTypes, preferredMinimumWidth, preferredMinimumHeight)`
returns a sorted sequence.  Previews that meet or exceed the requested dimensions
are ranked by closest fit; previews smaller than requested are deprioritised with
a 2 000-pixel penalty.

```kotlin
val photo: ServerFileWithMetadata = product.photo

// Find the best ≥ 400 px wide JPEG or WebP preview
val best = photo.previews(
    supportedTypes = setOf(MediaType.Image.JPEG, MediaType.Image.WebP),
    preferredMinimumWidth = 400,
).firstOrNull()

val url: ServerFile = best?.file ?: photo.original
```

Fall back to `original` when no matching preview exists (e.g., the image is a
non-image file type, or previews haven't been generated yet).

## Preview File Naming

Generated previews are stored alongside the original, in the same directory on
the file-storage backend.  The filename pattern is:

```
{original-name-without-extension}-{options}.{extension}
```

where `{options}` is the `toString()` of `MediaPreviewOptions` — for example:

| Options | Suffix |
|---------|--------|
| `MediaPreviewOptions(sizeInPixels = 200)` | `photo-200.jpg` |
| `MediaPreviewOptions(sizeInPixels = 800, type = JPEG)` | `photo-800-jpg.jpg` |
| `MediaPreviewOptions(forceRatio = 1.777…)` | `photo-ratio1.7777….jpg` |
| `MediaPreviewOptions.CorrectOddFeatures` | `photo-corrected.jpg` |

## File Validation: `@MimeType`

The `media` module registers annotation validators that can enforce MIME-type
and file-size constraints on `ServerFileWithMetadata` fields.  Add
`AnnotationValidators.Media` to your server's runtime and annotate your fields
with `@MimeType`:

> The annotation integration is illustrative — verify the exact `@MimeType`
> annotation signature and `AnnotationValidators.Media` wiring against
> `media/src/main/kotlin/…/media/validation.kt` before publishing.

## Gradle Dependencies

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.lightningkite.lightningserver:media:$lightningServerVersion")
    // media-shared is pulled in transitively via media
}
```

The `media` module is JVM-only.  If your multiplatform shared module needs
`ServerFileWithMetadata`, depend only on `media-shared`:

```kotlin
commonMain.dependencies {
    implementation("com.lightningkite.lightningserver:media-shared:$lightningServerVersion")
}
```

## Limitations

- Only image files are processed.  Non-image uploads (PDFs, videos, etc.) are
  stored unchanged; `width`/`height` remain `null` and `previews` stays empty.
- Video processing is not supported by this module.
- APNG and AVIF output formats are not yet implemented; options requesting them
  are skipped with a log warning.
- EXIF orientation is corrected in previews but other EXIF metadata (GPS, camera
  model, etc.) is not preserved.
- The synchronous interceptor processes on every update, even if the image field
  did not change.  (Background tasks detect field changes and skip unchanged
  records.)

## See Also

- [Files](files.md) — uploading files and the `ServerFile` type
- [Tasks](../guide/tasks.md) — background task registration and execution
- [Database](../guide/database.md) — table interceptors and the query DSL
