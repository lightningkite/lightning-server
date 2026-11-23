# Serialization

Lightning Server encodes bodies, query parameters, and WebSocket frames with kotlinx.serialization.  Media type coders
pick the format: the request's `Content-Type` chooses the decoder and its `Accept` header chooses the encoder.  Typed
endpoints do this for you; this chapter covers what to register, how to do it by hand in a raw handler, and how to add
types and formats.

<!-- sample: com/lightningkite/lightningserver/guide/samples/SerializationSamples.kt#serialization-raw -->
```kotlin
@Serializable
data class Salutation(val name: String, val excited: Boolean = false)

object GreetingServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    // POST /greet — decodes by Content-Type, encodes by Accept
    val greet = path.path("greet").post bind HttpHandler { request ->
        val input = request.body?.parse(Salutation.serializer()) ?: throw BadRequestException("Missing body")
        val output = input.copy(name = input.name.uppercase())
        HttpResponse(body = output.toTypedData(request.headers.accept))
    }

    // GET /greeting?name=Ann&excited=true
    val greeting = path.path("greeting").get bind HttpHandler { request ->
        val input = request.queryParameters(Salutation.serializer())
        HttpResponse.plainText("Hello, ${input.name}" + if (input.excited) "!" else ".")
    }
}
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/SerializationSamples.kt#serialization-raw-test -->
```kotlin
fun serializationRawTest() = GreetingServer.testBlocking(settings = {}) {
    val json = GreetingServer.greet.test(
        body = TypedData.text("""{"name":"ann"}""", MediaType.Application.Json),
    )
    check(json.body?.mediaType == MediaType.Application.Json)
    check(json.body?.text() == """{"name":"ANN","excited":false}""")

    val form = GreetingServer.greet.test(
        body = TypedData.text("name=ann&excited=true", MediaType.Application.FormUrlEncoded),
        headers = HttpHeaders(HttpHeader.Accept to "application/x-www-form-urlencoded"),
    )
    check(form.body?.mediaType == MediaType.Application.FormUrlEncoded)

    val query = GreetingServer.greeting.test(queryParameters = QueryParameters.parse("name=Ann&excited=true"))
    check(query.body?.text() == "Hello, Ann!")
}
```

Imports used in this chapter:

<!-- sample: com/lightningkite/lightningserver/guide/samples/SerializationSamples.kt#serialization-imports -->
```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.data.*
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.*
```

## Registering the coders

Nothing is registered by default.  Call `registerBasicMediaTypeCoders()` in the server's `init`, or typed endpoints,
error responses, and `toTypedData` fail with "No encoders found".

| Media type | Format | Notes |
|---|---|---|
| `application/json` | JSON | Priority 1, so it's the default when `Accept` names nothing registered; streams to and from the body |
| `application/x-www-form-urlencoded` | `FormDataFormat` | HTML forms |
| `application/x-lightningserver-kotlin-bytes` | `KotlinBytesFormat` | Compact binary format for Kotlin clients |

The registered JSON is deliberately lenient:

| Option | Value | Effect |
|---|---|---|
| `encodeDefaults` | `true` | Every field is sent, even when it equals its default |
| `ignoreUnknownKeys` | `true` | Extra fields from newer clients are ignored |
| `isLenient` | `true` | Accepts unquoted keys and similar |
| `allowStructuredMapKeys` | `true` | Maps with non-string keys |
| `allowSpecialFloatingPointValues` | `true` | `NaN`, `Infinity` |
| `useAlternativeNames` | `true` | `@JsonNames` aliases |
| `decodeEnumsCaseInsensitive` | `true` | `"active"` decodes as `ACTIVE` |
| `allowTrailingComma`, `allowComments` | `true` | |

## Content negotiation by hand

In a raw `HttpHandler`:

- `typedData.parse(serializer)` decodes with the coder for the body's media type.  An unregistered media type or a
  malformed body is a 400.
- `value.toTypedData(request.headers.accept)` encodes with the first registered coder that matches the `Accept`
  header, or JSON when none does.

## Query parameters

`request.queryParameters(serializer)` decodes the whole query string into a `@Serializable` class with
`FormDataFormat`.  Missing fields take their defaults, a repeated key (`tag=a&tag=b`) is joined with commas, and a
failure is a 400 with `detail = "serialization"`.  Typed GET endpoints decode their input this way.

## Your own types

`@Serializable` classes, sealed hierarchies, enums, and value classes need no registration.  Register a
`SerializersModule` for anything that does: contextual serializers for types you don't control, and polymorphic
hierarchies that aren't sealed.

<!-- sample: com/lightningkite/lightningserver/guide/samples/SerializationSamples.kt#serialization-contextual -->
```kotlin
// A type you don't control, so it can't carry @Serializable.
class Temperature(val celsius: Double)

object TemperatureSerializer : KSerializer<Temperature> {
    override val descriptor = PrimitiveSerialDescriptor("Temperature", PrimitiveKind.DOUBLE)
    override fun serialize(encoder: Encoder, value: Temperature) = encoder.encodeDouble(value.celsius)
    override fun deserialize(decoder: Decoder) = Temperature(decoder.decodeDouble())
}

@Serializable
data class SensorReading(val sensor: String, @Contextual val temperature: Temperature)

object TemperatureServer : ServerBuilder() {
    override val externalSerialization: Runtime<SerializersModule> = Runtime.Constant(
        SerializersModule { contextual(Temperature::class, TemperatureSerializer) }
    )

    init { registerBasicMediaTypeCoders() }

    val latest = path.path("latest").get bind ApiHttpHandler(
        summary = "Latest reading",
        auth = noAuth,
        implementation = { _: Unit -> SensorReading("roof", Temperature(21.5)) }
    )
}
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/SerializationSamples.kt#serialization-contextual-test -->
```kotlin
fun serializationContextualTest() = TemperatureServer.testBlocking(settings = {}) {
    check(TemperatureServer.latest.test().body?.text() == """{"sensor":"roof","temperature":21.5}""")
}
```

A server has two modules, both overridable on any `ServerBuilder`:

- `externalSerialization` is what clients see: HTTP bodies, query-string input, and WebSocket frames.  The basic
  coders use it.
- `internalSerialization` is what the server keeps: it is the module handed to services such as the database and
  cache, and it encodes WebSocket connection state.

Register a type in both when it is both sent and stored.  Modules from included builders are merged into the root's,
which is how `GlobalUploadEndpoints` installs the `ServerFile` serializer (see [Files](files.md)).  At runtime,
`serverRuntime.externalSerialization` and `internalSerialization` give a `Serialization` with ready-made `json`,
`jsonWithoutDefaults`, `jsonWithoutExplicitNulls`, `formDataFormat`, and `kotlinBytesFormat` instances built from that
module.

## Adding a format

Register a `MediaTypeCoder` for the media type.  `StringFormatMediaTypeCoder` and `BinaryFormatMediaTypeCoder` wrap any
kotlinx `StringFormat` or `BinaryFormat`:

<!-- sample: com/lightningkite/lightningserver/guide/samples/SerializationSamples.kt#serialization-coder -->
```kotlin
object PrettyServer : ServerBuilder() {
    init {
        registerBasicMediaTypeCoders()
        register(
            StringFormatMediaTypeCoder(
                format = { Json { prettyPrint = true; serializersModule = engine.externalSerialization.serializersModule } },
                mediaType = MediaType("application", "vnd.example.pretty+json"),
            )
        )
    }

    val latest = path.path("latest").get bind ApiHttpHandler(
        summary = "Latest greeting",
        auth = noAuth,
        implementation = { _: Unit -> Salutation("Ann") }
    )
}
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/SerializationSamples.kt#serialization-coder-test -->
```kotlin
fun serializationCoderTest() = PrettyServer.testBlocking(settings = {}) {
    val pretty = PrettyServer.latest.test(headers = HttpHeaders(HttpHeader.Accept to "application/vnd.example.pretty+json"))
    check(pretty.body?.text()?.contains("\n") == true)

    val compact = PrettyServer.latest.test()
    check(compact.body?.mediaType == MediaType.Application.Json)
}
```

The `format` lambda runs once, on first use, with a `ServerRuntime` in context, so it can read the serializers module
or settings.  When several coders handle one media type, the highest `priority` wins, and the highest priority overall
is the fallback encoder; JSON's is 1 and the default is 0.

## Watching what is sent

An `EncodingInterceptor` sees every value just before the server encodes it: response bodies, error bodies, and
WebSocket frames.  It must not change the value, but it may throw to stop the response.  The audit module uses this
to record which records reached a client.

<!-- sample: com/lightningkite/lightningserver/guide/samples/SerializationSamples.kt#serialization-interceptor -->
```kotlin
@Serializable
data class SecretApiKey(val key: String)

object GuardedServer : ServerBuilder() {
    init {
        registerBasicMediaTypeCoders()
        install(object : EncodingInterceptor {
            context(runtime: ServerRuntime)
            override suspend fun <T> beforeEncode(serializer: SerializationStrategy<T>, value: T) {
                if (value is SecretApiKey) throw ForbiddenException("API keys are never sent to clients")
            }
        })
    }

    val leak = path.path("leak").get bind ApiHttpHandler(
        summary = "Leak a key",
        auth = noAuth,
        implementation = { _: Unit -> SecretApiKey("secret") }
    )
}
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/SerializationSamples.kt#serialization-interceptor-test -->
```kotlin
fun serializationInterceptorTest() = GuardedServer.testBlocking(settings = {}) {
    check(GuardedServer.leak.test().status == HttpStatus.Forbidden)
}
```

## Next

- [Typed endpoints](typed-endpoints.md): negotiation without writing any of this.
- [Files](files.md): `ServerFile` and how it is serialized for clients.
- [Database](database.md): models stored with the internal module.
- [Client SDKs](client-sdks.md#calling-the-api-directly): the formats from a client's side.
