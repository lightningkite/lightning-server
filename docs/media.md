# Media Processing

The media module provides automatic image processing capabilities for Lightning Server applications, including thumbnail
generation, format conversion, and image optimization.

## Overview

The media module helps you automatically generate preview variants (thumbnails, different formats, etc.) of uploaded
images. It integrates seamlessly with the files module and database layer to provide both synchronous and asynchronous
processing options.

**Key Features:**

- Automatic thumbnail generation with configurable sizes
- Image format conversion (PNG, JPEG, WebP, TIFF, GIF, BMP)
- Quality control for lossy formats
- Aspect ratio enforcement
- EXIF orientation correction
- Both synchronous and background processing options

## Core Concepts

### ServerFileWithMetadata

The `ServerFileWithMetadata` data class wraps a server file with metadata about the file and any generated previews:

```kotlin
@Serializable
data class ServerFileWithMetadata(
    val original: ServerFile,
    val mimeType: MediaType? = null,
    val size: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val previews: List<ServerFileWithMetadataPreview> = listOf()
)
```

Use this in your models instead of plain `ServerFile` when you want automatic preview generation:

```kotlin
@Serializable
@GenerateDataClassPaths
data class Post(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val image: ServerFileWithMetadata? = null  // Instead of ServerFile
) : HasId<Uuid>
```

The processing helpers below address the field through a
`DataClassPath<T, ServerFileWithMetadata?>`, so a nullable field is the path of least
resistance.  For a non-nullable field, use `interceptImagesForProcessingNotNull`
instead of `interceptImagesForProcessing`; `processImagesInBackground` has no
non-nullable variant and requires a nullable field.

### MediaPreviewOptions

Configure how previews are generated using `MediaPreviewOptions`:

```kotlin
val thumbnailOptions = MediaPreviewOptions(
    sizeInPixels = 200,           // Maximum dimension (width or height)
    forceRatio = 16.0 / 9.0,      // Aspect ratio (optional)
    type = MediaType.Image.JPEG,   // Target format (optional)
    quality = 0.85                 // Quality for lossy formats (0.0 - 1.0)
)
```

**Common Presets:**

```kotlin
// Thumbnail
MediaPreviewOptions(sizeInPixels = 200, quality = 0.8)

// Web-optimized
MediaPreviewOptions(sizeInPixels = 1200, type = MediaType.Image.JPEG, quality = 0.85)

// Just fix orientation issues
MediaPreviewOptions.CorrectOddFeatures
```

## Processing Approaches

Lightning Server offers two ways to process images:

### 1. Synchronous Processing (Interceptors)

Process images immediately when records are created or updated. This blocks the API response until processing completes.

**Use when:**

- Previews must be available immediately
- Images are small and process quickly
- You need guaranteed preview generation

**Example:**

```kotlin
object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())

    // `signals` wraps the table on every access, so any write that goes through
    // this ModelInfo generates previews — no endpoint has to remember to.
    val postInfo = database.modelInfo<HasId<*>?, Post, Uuid>(
        auth = noAuth,
        tableName = "Post",
        signals = { table ->
            table.interceptImagesForProcessing(
                MediaPreviewOptions(sizeInPixels = 200),
                MediaPreviewOptions(sizeInPixels = 800),
                makePath = { it.image },
            )
        },
        permissions = { ModelPermissions.allowAll() },
    )

    val posts = path.path("posts") include ModelRestEndpoints(postInfo)
}
```

The interceptor must run against a live `Table<Post>`, which only exists inside a
`ServerRuntime` — that is exactly what `signals` provides.

### 2. Background Processing (Tasks)

Process images asynchronously after the record is saved. This provides faster API responses but previews may not be
immediately available.

**Use when:**

- Working with large images
- Processing time is unpredictable
- Fast API responses are critical

**Example:**

```kotlin
object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())

    val postInfo = database.modelInfo<HasId<*>?, Post, Uuid>(
        auth = noAuth,
        tableName = "Post",
        permissions = { ModelPermissions.allowAll() },
    )

    // Define the task; it listens for changes on postInfo's table
    val processPostImages = path.path("task").path("process-post-images") bind
        processImagesInBackground(
            info = postInfo,
            MediaPreviewOptions(sizeInPixels = 200),
            MediaPreviewOptions(sizeInPixels = 800),
            timeout = 5.minutes,
            makePath = { it.image },
        )
}
```

**Important:** Background tasks must be bound to a server path or they won't execute.

## Selecting Previews

The `ServerFileWithMetadata.previews()` method helps you find the best preview for a given use case:

```kotlin
val metadata: ServerFileWithMetadata = // ...

// Get the best preview for a 400x400 display area that supports JPEG and WebP
val bestPreview = metadata.previews(
    supportedTypes = setOf(MediaType.Image.JPEG, MediaType.Image.WebP),
    preferredMinimumWidth = 400,
    preferredMinimumHeight = 400
).firstOrNull()

// Use the preview file or fall back to original
val fileToUse = bestPreview?.file ?: metadata.original
```

The method returns previews sorted by best fit:

1. Previews that meet or exceed preferred dimensions (closest match first)
2. Previews smaller than preferred (with penalty, furthest match last)

## Complete Example

Here's a complete example integrating media processing into an API:

```kotlin
@Serializable
@GenerateDataClassPaths
data class Product(
    override val _id: Uuid = Uuid.random(),
    val name: String,
    val photo: ServerFileWithMetadata? = null,
    val createdAt: Instant = Clock.System.now()
) : HasId<Uuid>

object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val files = setting("files", Files.Settings())

    val productInfo = database.modelInfo<HasId<*>?, Product, Uuid>(
        auth = noAuth,
        tableName = "Product",
        // Use the interceptor for immediate availability
        signals = { table ->
            table.interceptImagesForProcessing(
                MediaPreviewOptions(sizeInPixels = 100),   // Thumbnail
                MediaPreviewOptions(sizeInPixels = 400),   // Medium
                MediaPreviewOptions(sizeInPixels = 1200),  // Large
                makePath = { it.photo },
            )
        },
        permissions = { ModelPermissions.allowAll() },
    )

    val products = path.path("products") include ModelRestEndpoints(productInfo)

    // Or use a background task for better response times — pick one, not both
    val processProductImages = path.path("task").path("process-images") bind
        processImagesInBackground(
            info = productInfo,
            MediaPreviewOptions(sizeInPixels = 100),
            MediaPreviewOptions(sizeInPixels = 400),
            MediaPreviewOptions(sizeInPixels = 1200),
            makePath = { it.photo },
        )
}
```

## Best Practices

### Choose Appropriate Sizes

Generate previews for your actual use cases:

```kotlin
MediaPreviewOptions(sizeInPixels = 100),   // List view thumbnails
MediaPreviewOptions(sizeInPixels = 400),   // Card displays
MediaPreviewOptions(sizeInPixels = 1200),  // Detail view
```

### Optimize for Format

Use JPEG for photos, PNG for graphics with transparency:

```kotlin
// Photos - use JPEG with quality control
MediaPreviewOptions(
    sizeInPixels = 800,
    type = MediaType.Image.JPEG,
    quality = 0.85
)

// Graphics/logos - preserve as PNG
MediaPreviewOptions(
    sizeInPixels = 400,
    type = MediaType.Image.PNG
)
```

### Balance Quality and Size

Lower quality for thumbnails, higher for display images:

```kotlin
MediaPreviewOptions(sizeInPixels = 100, quality = 0.7),  // Thumbnails
MediaPreviewOptions(sizeInPixels = 1200, quality = 0.9), // Full display
```

### Handle Non-Images Gracefully

The processing functions only process image files - other file types are returned unchanged:

```kotlin
// This is safe even if some uploads are PDFs or other non-image files
signals = { table ->
    table.interceptImagesForProcessing(
        MediaPreviewOptions(sizeInPixels = 200),
        makePath = { it.attachment },
    )
}
```

## Limitations

- **Supported formats:** PNG, JPEG, WebP, TIFF, GIF, BMP
- **Unsupported formats:** APNG, AVIF (will be skipped with a warning)
- **Processing:** Only images are processed; videos and other media types are stored as-is
- **EXIF:** Orientation is corrected but other EXIF metadata is not preserved in previews

## Troubleshooting

### Previews Not Generated

**Background tasks not executing:**

- Ensure the task is bound to a server path
- Check server logs for warnings about unregistered tasks

**Synchronous processing slow:**

- Consider switching to background processing for large images
- Reduce the number of preview sizes being generated

### Memory Issues

If processing very large images causes memory issues:

- Use background processing with appropriate timeouts
- Limit the maximum upload size
- Generate fewer preview variants

### File Storage

Preview files are stored in the same directory as the original with suffixes:

- Original: `photo.jpg`
- Previews: `photo-200-jpg.jpg`, `photo-800-jpg.jpg`, etc.

Ensure your file storage backend has sufficient space for original + all previews.

## See Also

- [Files Module](files.md) - File upload and storage
- [Database](database.md) - Database operations and interceptors
- [Tasks](tasks.md) - Background task processing
