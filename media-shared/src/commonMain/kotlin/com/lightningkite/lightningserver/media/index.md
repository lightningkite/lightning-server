# Media Shared Package

This package contains the shared multiplatform models for media processing in Lightning Server.

## Files

### models.kt

- **ServerFileWithMetadata** - Multiplatform data class representing a file with metadata and preview variants
- **ServerFileWithMetadataPreview** - Multiplatform data class representing a preview variant

## Key Features

### Cross-Platform Models

These models work across all Lightning Server supported platforms:

- JVM (server-side)
- JavaScript (client-side web)
- iOS
- Android
- macOS

### Preview Selection

The `previews()` method provides intelligent selection of the best preview variant based on:

- Supported media types
- Preferred minimum width
- Preferred minimum height

Results are sorted by best fit, with previews that meet or exceed requirements ranked highest.

## Usage Example

```kotlin
val metadata: ServerFileWithMetadata = // ...

// Find best preview for 400x400 display
val preview = metadata.previews(
    supportedTypes = setOf(MediaType.Image.JPEG, MediaType.Image.WebP),
    preferredMinimumWidth = 400,
    preferredMinimumHeight = 400
).firstOrNull()

val fileToDisplay = preview?.file ?: metadata.original
```

## Integration

These models are used in conjunction with the JVM-only `media` module which provides the actual processing
implementation via the Scrimage library.

See [media.md](../../../../../../docs/media.md) for full documentation.
