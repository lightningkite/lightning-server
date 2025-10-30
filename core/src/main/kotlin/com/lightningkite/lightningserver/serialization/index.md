# Package com.lightningkite.lightningserver.serialization

This package provides the serialization infrastructure for Lightning Server, enabling automatic encoding and decoding of data in various formats for HTTP requests and responses.

## Core Components

### Configuration

- **[Serialization.kt](Serialization.kt)** - Central configuration class that provides preconfigured instances of various serialization formats (JSON, form data, binary) with a shared `SerializersModule`. All formats are configured with sensible defaults for web API development.

### Media Type Support

- **[MediaTypeCoder.kt](MediaTypeCoder.kt)** - Core interfaces for serialization:
  - `MediaTypeEncoder` - Encodes Kotlin objects to typed data
  - `MediaTypeDecoder` - Decodes typed data to Kotlin objects
  - `MediaTypeCoder` - Combined encoder/decoder interface

  These interfaces support both HTTP body content and WebSocket frames, with optional streaming capabilities.

- **[media.kt](media.kt)** - Registries and extension functions for media types:
  - `MediaTypeEncoderRegistry` - Maps media types to encoders
  - `MediaTypeDecoderRegistry` - Maps media types to decoders
  - Extension functions for content negotiation and automatic encoder/decoder selection
  - Query parameter parsing utilities

### Format Implementations

- **[registerBasicMediaTypeCoders.kt](registerBasicMediaTypeCoders.kt)** - Concrete implementations and registration:
  - `BinaryFormatMediaTypeCoder` - Wrapper for binary formats
  - `StringFormatMediaTypeCoder` - Wrapper for string-based formats
  - `JsonMediaTypeCoder` - Specialized JSON coder with streaming support
  - `registerBasicMediaTypeCoders()` - Extension to register JSON, form data, and binary formats

- **[FormDataFormat.kt](FormDataFormat.kt)** - StringFormat implementation for `application/x-www-form-urlencoded` data. Handles URL encoding/decoding and provides utility methods for working with maps and lists. Automatically wraps primitive types and enums for compatibility with the Properties format.

### Utilities

- **[serializerOrContextual.kt](serializerOrContextual.kt)** - Utility functions for obtaining serializers at runtime, falling back to contextual serializers when built-in serializers aren't available. Useful for generic code that works with both `@Serializable` types and custom serialized types.

## Key Features

1. **Multiple Format Support** - JSON, URL-encoded form data, and binary formats out of the box
2. **Content Negotiation** - Automatic encoder/decoder selection based on HTTP headers
3. **Streaming** - Efficient handling of large payloads via Source/Sink
4. **Priority System** - Control which encoder/decoder is used when multiple support the same media type
5. **Extensibility** - Easy registration of custom media type coders
6. **WebSocket Support** - All coders can handle WebSocket frames in addition to HTTP bodies

## Usage Example

```kotlin
object Server : ServerBuilder() {
    init {
        // Registers JSON, form data, and binary formats
        registerBasicMediaTypeCoders()
    }

    val api = path.path("api").path("users").post bind HttpHandler { request ->
        with(serverRuntime) {
            // Automatically decode based on Content-Type
            val user = request.body!!.parse(User.serializer())

            // Process user...

            // Automatically encode based on Accept header
            user.toTypedData(request.headers.accept)
        }
    }
}
```

## Related Documentation

See [/docs/serialization.md](../../../../docs/serialization.md) for comprehensive usage guide and examples.
