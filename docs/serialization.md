# Serialization

Last updated October 29, 2025 (`version-5`)

Lightning Server provides a comprehensive serialization system for handling multiple data formats in HTTP requests and
responses. The serialization package enables automatic encoding/decoding of data using KotlinX Serialization with
support for JSON, form data, and binary formats.

## Overview

The serialization system consists of three main components:

1. **Serialization Configuration** - Centralized setup for all serialization formats
2. **Media Type Coders** - Pluggable encoders/decoders for different content types
3. **Utility Functions** - Helpers for working with serializers and media types

## Basic Usage

### Setting Up Serialization

The framework automatically registers basic media type coders during server setup:

```kotlin
object Server : ServerBuilder() {
    init {
        // Basic coders are registered automatically
        registerBasicMediaTypeCoders()
    }
}
```

### Supported Formats

Out of the box, Lightning Server supports:

- **application/json** - JSON format with streaming support (highest priority)
- **application/x-www-form-urlencoded** - HTML form data and query parameters
- **application/x-lightningserver-kotlin-bytes** - Efficient binary format for internal use

### JSON Configuration

The default JSON format is configured with lenient settings suitable for web APIs:

```kotlin
val serialization = Serialization()
val json = serialization.json

// Configuration:
// - encodeDefaults: true - Includes default values
// - ignoreUnknownKeys: true - Won't fail on extra fields
// - isLenient: true - Accepts non-strict JSON
// - allowStructuredMapKeys: true - Maps with complex keys
// - allowSpecialFloatingPointValues: true - NaN, Infinity
// - allowTrailingComma: true - Trailing commas allowed
// - allowComments: true - JSON comments supported
```

For smaller payloads, use `jsonWithoutDefaults` which omits default values:

```kotlin
val compactJson = serialization.jsonWithoutDefaults
```

## Form Data Encoding

The `FormDataFormat` class handles URL-encoded form data commonly used in HTML forms and query parameters.

### Encoding Data

```kotlin
@Serializable
data class SearchQuery(val term: String, val limit: Int = 10)

val format = FormDataFormat(EmptySerializersModule())
val query = SearchQuery("kotlin", 20)

// Encode to URL-encoded string
val encoded = format.encodeToString(SearchQuery.serializer(), query)
// Result: "term=kotlin&limit=20"

// Encode to map
val map = format.encodeToMap(SearchQuery.serializer(), query)
// Result: mapOf("term" to "kotlin", "limit" to "20")
```

### Decoding Data

```kotlin
// Decode from URL-encoded string
val decoded = format.decodeFromString(
    SearchQuery.serializer(),
    "term=kotlin&limit=20"
)

// Decode from map (useful for query parameters)
val fromMap = format.decodeFromMap(
    SearchQuery.serializer(),
    mapOf("term" to "kotlin", "limit" to "20")
)
```

### Primitive Type Wrapping

**Important:** Primitive types and enums are automatically wrapped in a box object because the underlying Properties
format requires structure-kind descriptors. This is transparent to users but may affect performance for simple types.

```kotlin
// Primitive values are automatically wrapped
val encoded = format.encodeToMap(String.serializer(), "test")
// Result: mapOf("value" to "test")

val decoded = format.decodeFromMap(String.serializer(), mapOf("value" to "test"))
// Result: "test"
```

## Working with Query Parameters

Lightning Server provides a convenient extension for parsing query parameters:

```kotlin
@Serializable
data class PageParams(val page: Int = 1, val size: Int = 20)

val endpoint = path.path("users").get bind HttpHandler { request ->
    with(serverRuntime) {
        val params = request.queryParameters(PageParams.serializer())
        // Use params.page and params.size
    }
}
```

Multiple values for the same parameter are joined with commas.

## Custom Media Type Coders

You can register custom coders for additional media types:

### String-Based Format

```kotlin
class XmlCoder : StringFormatMediaTypeCoder(
    format = { MyXmlFormat(serverRuntime.externalSerialization.serializersModule) },
    mediaType = MediaType("application", "xml")
)

// Register in your server
register(XmlCoder())
```

### Binary Format

```kotlin
class ProtobufCoder : BinaryFormatMediaTypeCoder(
    format = { ProtoBuf { serializersModule = serverRuntime.externalSerialization.serializersModule } },
    mediaType = MediaType("application", "protobuf")
)

register(ProtobufCoder())
```

### Setting Priority

Coders with higher priority values are preferred when multiple coders support the same media type:

```kotlin
class MyJsonCoder : MediaTypeCoder {
    override val priority: Float = 2f  // Higher than default JSON (1f)
    override val mediaType = MediaType.Application.Json
    // ... implementation
}
```

## Serializer Utilities

### Getting Serializers at Runtime

The `serializerOrContextual()` function provides a convenient way to obtain serializers:

```kotlin
// For @Serializable types
val serializer = serializerOrContextual<MyData>()

// Falls back to contextual serializers for non-@Serializable types
val customSerializer = serializerOrContextual<CustomType>()
```

**Note:** This uses `EmptySerializersModule` for lookup, so custom serializers registered in your module will fall back
to `ContextualSerializer`.

## Advanced Topics

### Content Negotiation

The framework automatically selects appropriate encoders/decoders based on Content-Type and Accept headers:

```kotlin
context(serverRuntime: ServerRuntime)
suspend fun handleRequest(data: TypedData): TypedData {
    // Parse incoming data using its media type
    val input = data.parse(MyInput.serializer())

    // Process...
    val output = processData(input)

    // Encode response using accepted media types
    return output.toTypedData(
        accepts = listOf(
            MediaType.Application.Json,
            MediaType("application", "xml")
        )
    )
}
```

### Streaming Support

JSON encoding supports efficient streaming for large payloads:

```kotlin
// The JsonMediaTypeCoder automatically uses Source/Sink
// for efficient streaming when available
val largeData = generateLargeDataset()
val typedData = largeData.toTypedData(listOf(MediaType.Application.Json))
// Uses streaming internally via Json.encodeToSink()
```

### Custom Serializers Module

Share a consistent serializers module across all formats:

```kotlin
val myModule = SerializersModule {
    contextual(UUID::class, UUIDSerializer)
    contextual(Instant::class, InstantSerializer)
}

val serialization = Serialization(myModule)
// All formats now use myModule
```

## Best Practices

1. **Use the default JSON configuration** - It's designed for web APIs and handles most edge cases
2. **Prefer form data for query parameters** - More efficient than parsing JSON query strings
3. **Register custom coders early** - Do it in your ServerBuilder initialization
4. **Share SerializersModule** - Use a single module for internal and external serialization
5. **Test with different media types** - Ensure your endpoints work with all supported formats

## See Also

- [Endpoints](endpoints.md) - Using serialization in HTTP endpoints
- [Typed Endpoints](typed-endpoints.md) - Type-safe APIs with automatic serialization
- [KotlinX Serialization](https://github.com/Kotlin/kotlinx.serialization) - Underlying serialization library
