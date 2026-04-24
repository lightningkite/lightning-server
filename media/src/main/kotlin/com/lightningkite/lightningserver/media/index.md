# Media Package

This package provides image processing capabilities for Lightning Server, including automatic preview generation, format
conversion, and optimization.

## Files

### models.kt (media-shared)

- **ServerFileWithMetadata** - Data class wrapping a server file with metadata and preview variants
- **ServerFileWithMetadataPreview** - Represents a single preview variant of a media file
- Provides methods for selecting the best preview based on supported formats and desired dimensions

### MediaPreviewOptions.kt

- **MediaPreviewOptions** - Configuration for generating media previews (size, format, quality, aspect ratio)
- **FileMediaInfo** - Information about a processed media file
- **ImmutableImage.apply()** - Extension function that applies preview options to an image
- Supports PNG, JPEG, WebP, TIFF, GIF, and BMP output formats

### processing.kt

- **ServerFileWithMetadata.process()** - Processes a file to generate preview variants
- **processImagesInBackground()** - Creates a background task for asynchronous image processing
- **interceptImagesForProcessing()** - Wraps a database table to process images synchronously on create/update
- **interceptImagesForProcessingNotNull()** - Non-nullable variant of the interceptor

## Key Concepts

### Processing Approaches

1. **Synchronous (Interceptors)** - Process images immediately during database operations
2. **Asynchronous (Background Tasks)** - Process images in the background after saving

### Preview Selection

The `ServerFileWithMetadata.previews()` method sorts previews by best fit for requested dimensions, preferring previews
that meet or exceed requirements.

## Usage Example

```kotlin
context(runtime: ServerRuntime)
object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())

    val posts = database
        .table<Post>()
        .interceptImagesForProcessing(
            MediaPreviewOptions(sizeInPixels = 200),
            MediaPreviewOptions(sizeInPixels = 800),
            makePath = { it.path { it.image } }
        )
}
```

See [media.md](../../../../../docs/media.md) for comprehensive documentation.
