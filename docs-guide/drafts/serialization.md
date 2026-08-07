> # ⚠️ UNREVIEWED FIRST DRAFT — DO NOT PUBLISH
> Auto-generated first pass. Code samples are modeled on the current source but have **NOT** been compiled, drift-checked, or reviewed. This page is intentionally **not** in the site nav. Before publishing: port samples into the drift-checked `docs-guide/src/samples` module, run the fresh-eyes judge, and delete this banner.

# Serialization & ServerFile

Lightning Server serializes all HTTP bodies, query parameters, and stored values using
KotlinX Serialization.  The framework handles format selection automatically via content
negotiation: it inspects the `Content-Type` header on incoming requests and the `Accept`
header on outgoing responses, then picks the registered coder whose media type matches.
You rarely need to touch serialization directly — but knowing the shape of the system
helps when you add custom types or a custom wire format.

## Imports

All examples in this chapter use the following imports:

```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.serialization.*
import com.lightningkite.services.data.*
import com.lightningkite.services.files.*
import kotlinx.serialization.*
import kotlinx.serialization.modules.*
```

## Registering the Built-In Coders

Typed endpoints (see [Typed Endpoints](typed-endpoints.md)) call `registerBasicMediaTypeCoders()`
for you.  If you use raw `HttpHandler`s and want the same content negotiation, add the
call yourself in your `ServerBuilder`'s `init {}` block:

```kotlin
object Server : ServerBuilder() {
    init {
        registerBasicMediaTypeCoders()
    }
    // ... endpoints
}
```

`registerBasicMediaTypeCoders()` is a `ServerBuilder` extension that registers three coders:

| Media type | Class | Notes |
|---|---|---|
| `application/json` | `JsonMediaTypeCoder` | Priority 1.0 — preferred encoder; streaming via `Source`/`Sink` |
| `application/x-www-form-urlencoded` | `StringFormatMediaTypeCoder` wrapping `FormDataFormat` | Used for HTML form bodies and query-parameter parsing |
| `application/x-lightningserver-kotlin-bytes` | `BinaryFormatMediaTypeCoder` wrapping `KotlinBytesFormat` | Compact binary format for internal SDK calls |

The `priority` value controls which encoder wins when a client sends `Accept: */*`.
JSON is 1.0; the other two coders default to 0.0, so JSON is always the fallback.

## The JSON Configuration

The JSON instance registered by `registerBasicMediaTypeCoders()` is intentionally lenient:

```kotlin
// Illustrative — matches the configuration in registerBasicMediaTypeCoders.kt exactly.
Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    isLenient = true
    allowStructuredMapKeys = true
    prettyPrint = false
    allowSpecialFloatingPointValues = true
    useAlternativeNames = true
    decodeEnumsCaseInsensitive = true
    allowTrailingComma = true
    allowComments = true
}
```

`encodeDefaults = true` means every field is serialized, even if it equals the Kotlin
default.  `ignoreUnknownKeys = true` means a client can send extra fields without causing
a `400`.  `isLenient = true` allows non-standard JSON such as unquoted keys.

The `Serialization` class (available as `serverRuntime.externalSerialization` and
`serverRuntime.internalSerialization`) provides three pre-built `Json` instances:

| Property | `encodeDefaults` | `explicitNulls` | Typical use |
|---|---|---|---|
| `json` | `true` | `true` | External API serialization |
| `jsonWithoutDefaults` | `false` | `true` | Compact payloads |
| `jsonWithoutExplicitNulls` | `true` | `false` | JSON-RPC or similar protocols |

## Content Negotiation

When Lightning Server processes a request body, it calls `TypedData.parse(serializer)`.
This inspects the `Content-Type` header and looks up a registered `MediaTypeDecoder` in
the runtime's decoder registry.  If no decoder is registered for that content type, the
call throws `BadRequestException`.

When Lightning Server produces a response body, it calls `T.toTypedData(accepts)`.
The `accepts` list comes from the request's `Accept` header (or `listOf()` when there is
none).  The encoder registry finds the first matching encoder; if none match, the highest-
priority encoder is used as the fallback.

```kotlin
// Illustrative — shows the two low-level helpers used by typed endpoints internally.
// You do not normally call these directly; ApiHttpHandler does it for you.

context(serverRuntime: ServerRuntime)
suspend fun exampleRoundTrip(request: HttpRequest<TypedData>): TypedData {
    // Decode the incoming body using the Content-Type header.
    val input = request.body!!.parse(MyInput.serializer())

    // Process...
    val output = MyOutput(result = "ok")

    // Encode using the client's Accept header preferences.
    val accepts = request.headers.getAll("Accept")
        .flatMap { it.split(',') }
        .map { MediaType(it.trim()) }
    return output.toTypedData(accepts, MyOutput.serializer())
}
```

## Two Serialization Contexts: External vs Internal

`ServerRuntime` exposes two separate `Serialization` instances:

- **`externalSerialization`** — used for HTTP API bodies; includes contextual
  serializers for types like `ServerFile` that have a different wire representation
  than their internal storage form.
- **`internalSerialization`** — used for database storage, cache values, and other
  server-to-server communication; may omit or simplify contextual overrides.

Both default to `EmptySerializersModule()`.  Override `internalSerialization` or
`externalSerialization` in your `ServerBuilder` to supply a richer module:

```kotlin
// Illustrative — overrides both serialization contexts with a custom module.
object Server : ServerBuilder() {
    override val externalSerialization: Runtime<SerializersModule> = Runtime {
        SerializersModule {
            // Register contextual serializers visible to external API bodies.
            contextual(MyCustomType::class, MyCustomTypeSerializer)
        }
    }

    override val internalSerialization: Runtime<SerializersModule> = Runtime {
        SerializersModule {
            // Internal storage uses a different (possibly lighter) serializer.
            contextual(MyCustomType::class, MyCustomTypeStorageSerializer)
        }
    }
}
```

When your server includes sub-builders (via `include`), their `externalSerialization`
and `internalSerialization` are merged with the root at `build()` time.
`UploadEarlyEndpoint` (see [ServerFile](#serverfile-serialization) below) uses this
mechanism to inject the `ExternalServerFileSerializer` contextual entry.

## Polymorphic and Sealed Types

KotlinX Serialization requires that polymorphic hierarchies be declared in a
`SerializersModule`.  Register your hierarchy in `externalSerialization` (if the
hierarchy appears in API bodies) or `internalSerialization` (if it only appears
in storage) or both.

```kotlin
// Illustrative — a sealed class serializable as an API response.
@Serializable
sealed class Shape {
    @Serializable
    @SerialName("circle")
    data class Circle(val radius: Double) : Shape()

    @Serializable
    @SerialName("rectangle")
    data class Rectangle(val width: Double, val height: Double) : Shape()
}

object Server : ServerBuilder() {
    override val externalSerialization: Runtime<SerializersModule> = Runtime {
        SerializersModule {
            polymorphic(Shape::class) {
                subclass(Shape.Circle::class, Shape.Circle.serializer())
                subclass(Shape.Rectangle::class, Shape.Rectangle.serializer())
            }
        }
    }
}
```

The registered module is merged into the `Serialization` instance used by all
registered media type coders through `registerBasicMediaTypeCoders()`.

## Adding a Custom Media Type Coder

To expose an additional wire format (XML, Protobuf, CSV, etc.), implement
`MediaTypeCoder` or extend one of the two base classes, then call `register()` in
your `ServerBuilder`.

**String-based formats** — wrap a KotlinX `StringFormat`:

```kotlin
// Illustrative — registers an XML coder using a hypothetical MyXmlFormat.
class XmlMediaTypeCoder : StringFormatMediaTypeCoder(
    format = { MyXmlFormat(serverRuntime.externalSerialization.serializersModule) },
    mediaType = MediaType("application", "xml")
)

object Server : ServerBuilder() {
    init {
        registerBasicMediaTypeCoders()
        register(XmlMediaTypeCoder())
    }
}
```

**Binary formats** — wrap a KotlinX `BinaryFormat`:

```kotlin
// Illustrative — registers a Protobuf coder.
class ProtobufMediaTypeCoder : BinaryFormatMediaTypeCoder(
    format = { ProtoBuf { serializersModule = serverRuntime.externalSerialization.serializersModule } },
    mediaType = MediaType("application", "x-protobuf")
)
```

Both base classes cache the format instance after first access for performance.  The
`format` lambda runs inside a `ServerRuntime` context so it can access settings or other
runtime values.

**Priority** — the `priority: Float` property (default `0.0`) controls which coder is
preferred when multiple coders match.  JSON is `1.0`.  If you want your coder to be
the fallback encoder for `Accept: */*`, set priority above `1.0`.

```kotlin
// Illustrative — makes MyXmlCoder the default when no Accept header is present.
class XmlMediaTypeCoder : StringFormatMediaTypeCoder(/* ... */) {
    override val priority: Float get() = 2.0f
}
```

## ServerFile Serialization

`ServerFile` is a value class wrapping a `String` file location:

```kotlin
// From service-abstractions: files-client/ServerFile.kt
@JvmInline
@Serializable(DeferToContextualServerFileSerializer::class)
value class ServerFile(val location: String)
```

Its `@Serializable` annotation references `DeferToContextualServerFileSerializer`, which
defers to whatever serializer is registered in the current `SerializersModule` for
`ServerFile::class`.  On the client side (where no contextual override is present),
`DirectServerFileSerializer` kicks in — it reads and writes the raw URL string unchanged.

**On the server side**, `UploadEarlyEndpoint` overrides `externalSerialization` to
inject `ExternalServerFileSerializer` as the contextual entry:

```kotlin
// Illustrative — this is what UploadEarlyEndpoint does internally.
override val externalSerialization: Runtime<SerializersModule> = Runtime.Cached {
    SerializersModule {
        contextual(ServerFile::class, serializer())   // serializer() is ExternalServerFileSerializer
    }
}
```

This means **any endpoint that shares a ServerBuilder tree with an `UploadEarlyEndpoint`
gets the secure serializer automatically**.  You do not register it manually.

### What ExternalServerFileSerializer Does

On **serialize** (server → client): converts the file's internal URL to a
presigned URL so the client can access the file without credentials.  If a URL does not
belong to any registered file system root, the serializer rejects it by default
(`ForeignUrlHandling.ERROR`).

On **deserialize** (client → server): validates the incoming URL.  It accepts:

| Prefix | Meaning |
|---|---|
| `future:...` | File is in the upload jail; must be scanned before use |
| `future-prescanned:...` | File was already scanned via the `/verify` endpoint |
| Regular URL | Must match a known file system root |

`data:` URLs (inline base64) are rejected by default; clients must use the upload
endpoint instead.  The legacy pass-through can be re-enabled with
`ExternalServerFileSerializer.inlineScanOnDeserialize = true`, but this is not
recommended in production.

### The Upload Flow

1. Client calls `GET /upload` (the `UploadEarlyEndpoint.endpoint` handler) — receives
   a presigned `uploadUrl` and a time-limited `futureCallToken`.
2. Client PUTs the file bytes to `uploadUrl`.
3. *(Optional)* Client calls `POST /upload/verify` with the token — the server scans
   and moves the file to the ready directory, returning a `future-prescanned:` token
   for faster subsequent use.
4. Client includes `futureCallToken` as the value of a `ServerFile` field in a later
   request body.  `ExternalServerFileSerializer.deserialize` validates the token and
   resolves it to the final internal URL.

> **Gotcha: one `UploadEarlyEndpoint` per server tree.** If you declare multiple
> instances in the same `ServerBuilder` tree they will both try to register a
> contextual serializer for `ServerFile`, which causes a
> `ConflictingSerializerException` at startup.  Declare it once and share the
> reference.

## What's Next

- **Files** — how to declare `PublicFileSystem.Settings()`, wire a file system backend,
  and configure `UploadEarlyEndpoint` for your server (see the files draft chapter).
- **Typed Endpoints** — typed endpoints call `registerBasicMediaTypeCoders()` and use
  `parse`/`toTypedData` automatically; you usually only need to read this chapter when
  working with raw `HttpHandler`s or custom wire formats.
- **Database** — `internalSerialization` is the module used when reading and writing
  database documents; add your polymorphic or contextual registrations there if your
  models need them.
