# Client SDKs

Lightning Server generates typed clients from your server definition: a Kotlin Multiplatform client (`FetcherSdk`)
and a TypeScript client (`TypescriptFetcherSdk`).  Every typed endpoint becomes a function with the same input and
output types, so the client can't drift from the server.  Generation reads the definition offline: no running server,
database, or network.

<!-- sample: com/lightningkite/lightningserver/guide/samples/ClientSdksSamples.kt#sdk-server -->
```kotlin
@Serializable
data class Memo(val id: Int, val text: String)

class MemoEndpoints : ServerBuilder() {
    val list = path.get bind ApiHttpHandler(
        summary = "List memos",
        auth = noAuth,
        implementation = { _: Unit -> listOf(Memo(1, "Hello")) }
    )
}

class StatusModule : ServerBuilder() {
    val ping = path.path("ping").get bind ApiHttpHandler(
        summary = "Ping",
        auth = noAuth,
        implementation = { _: Unit -> "pong" }
    )
}

object SdkServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    // Its own namespace, named from the class: interface MemoApi, property `memo`
    val memos = path.path("memos") module MemoEndpoints()

    // Its own namespace, named explicitly
    val status = path.path("status") module StatusModule().withSdkInfo("StatusApi", "status")

    // Directly on the root Api
    val version = path.path("version").get bind ApiHttpHandler(
        summary = "Get version",
        auth = noAuth,
        implementation = { _: Unit -> "1.0.0" }
    )
}
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/ClientSdksSamples.kt#sdk-generate -->
```kotlin
fun generateSdks(output: KFile) {
    FetcherSdk(packageName = "com.example.client").writeUsingDefaultSettings(SdkServer, output.then("kotlin"))
    TypescriptFetcherSdk().writeUsingDefaultSettings(SdkServer, output.then("typescript"))
}
```

Wire `generateSdks` into your `main` as a command (the demo uses a `sdk` command run with `./gradlew run --args=sdk`)
and commit the output, or regenerate it in CI.

Imports for this chapter:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ClientSdksSamples.kt#sdk-imports -->
```kotlin
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.serialization.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.typed.contract.*
import com.lightningkite.lightningserver.typed.kschema.lightningServerKSchemaFromDefaultRuntime
import com.lightningkite.lightningserver.typed.sdk.*
import com.lightningkite.lightningserver.typed.sdk.SDK.writeUsingDefaultSettings
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.withSdkInfo
import com.lightningkite.services.kfile.KFile
import kotlinx.serialization.*
```

## What gets generated

Only typed endpoints (`ApiHttpHandler`, `ApiWebSocketHandler`) appear; raw `HttpHandler`s don't.

- Each endpoint becomes a function named from its `summary` in camelCase (`"List memos"` is `listMemos`), or from
  `functionName` when given.  Path arguments come first, then the input when it isn't `Unit`.
- The `summary`, `description`, auth requirement, and error cases become doc comments (`includeDocComments = false`
  turns them off).
- Typed WebSockets become functions returning `ClientWebSocket<Input, Output>` in Kotlin.  The TypeScript generator
  skips WebSockets.
- Built-in endpoint groups such as [Model REST](model-rest.md) map to shared client interfaces from `typed-shared`
  (`ClientModelRestEndpoints`), so every model gets the same client API.

For the server above, `FetcherSdk` writes `Api.kt` (interfaces) and `LiveApi.kt` (implementations over a `Fetcher`):

```kotlin
// Api.kt (doc comments and the deprecated withHeaderCalculator omitted)
interface Api {
	fun withMetadata(block: suspend RequestMetadata.() -> Unit): Api
	suspend fun getVersion(): kotlin.String

	interface MemoApi {
		suspend fun listMemos(): List<com.lightningkite.lightningserver.guide.samples.Memo>
	}
	val memo: MemoApi

	interface StatusApi {
		suspend fun ping(): kotlin.String
	}
	val status: StatusApi
}

// LiveApi.kt
class LiveApi(val fetcher: Fetcher) : Api {
	override fun withMetadata(block: suspend RequestMetadata.() -> Unit): LiveApi =
		LiveApi(fetcher.withMetadata(block))
	override suspend fun getVersion(): kotlin.String =
		fetcher("version", HttpMethod.GET, kotlin.Unit.serializer(), kotlin.Unit, kotlin.String.serializer())

	inner class LiveMemoApi : Api.MemoApi {
		override suspend fun listMemos(): List<com.lightningkite.lightningserver.guide.samples.Memo> =
			fetcher("memos", HttpMethod.GET, kotlin.Unit.serializer(), kotlin.Unit, ListSerializer(com.lightningkite.lightningserver.guide.samples.Memo.serializer()))
	}
	override val memo = LiveMemoApi()
	// ... LiveStatusApi likewise
}
```

Data classes aren't copied: the Kotlin client uses your own `@Serializable` types, so put them in a shared
(multiplatform) module that both the server and the client depend on.

`TypescriptFetcherSdk` writes `models.ts` (TypeScript interfaces for the data types), `Api.ts`, and `LiveApi.ts`; SDK
modules become nested objects (`api.memo.listMemos()`).  It needs the
`@lightningkite/lightning-server-simplified` npm package, which provides the runtime types and the `Fetcher`.

| Kotlin | TypeScript |
|---|---|
| `String`, `Char` | `string` |
| `Int`, `Long`, `Float`, `Double` | `number` |
| `Boolean` | `boolean` |
| `List<T>`, `Set<T>` | `Array<T>` |
| `Map<String, V>` | `Record<string, V>` |
| `T?` | `T \| null \| undefined` |
| `Unit` | `void` |
| data class | `interface` |
| enum | `enum`, or a union `type` with `erasableTypes = true` |

## SDK modules

`include` mounts a builder's endpoints flat into its parent's namespace.  `module` mounts them as their own interface,
reached through a property on the parent:

- `path.path("memos") module MemoEndpoints()` infers the names from the class: the `Endpoints` or `Module` suffix is
  dropped and `Api` appended (`MemoApi`), and the property is the camelCase name without `Api` (`memo`).
- `.withSdkInfo("StatusApi", "status")` names them explicitly; required for anonymous objects.

`module` needs an import from `com.lightningkite.lightningserver.typed.sdk`.

## Generator options

| Option | Default | |
|---|---|---|
| `FetcherSdk(packageName)` | required | Package of the generated Kotlin files |
| `rootInfo` | `SdkModule.Info("Api")` | Root interface name; the implementation is `Live` + that name |
| `fileStructure` | `Structure.MultipleFiles(...)` | Or `Structure.SingleFile(filename)` |
| `includeDocComments` | `true` | |
| `erasableTypes` (TypeScript only) | `false` | Emit union types instead of `enum`, for TypeScript's `erasableSyntaxOnly` |

Besides a folder, both generators can write to any `Archive`, such as `Archive.zip(zipOutputStream)`.

During development, [`MetaEndpoints`](meta-admin.md) also serves the current SDK from the running server:
`/meta/docs/sdk.ts`, `/meta/docs/sdk.ts.zip`, `/meta/docs/sdk.kt`, and `/meta/docs/sdk.kt.zip`.

## Using the Kotlin client

`LiveApi` takes a `Fetcher`, the interface that performs requests.  Lightning Server defines the interface in
`typed-shared`; the KiteUI combo package (`lightning-server-kiteui`) provides implementations, including one that batches
calls through [`/meta/bulk`](bulk-endpoints.md).

To attach credentials or other metadata to every request, use `withMetadata`.  The block runs before each request, on a
copy of the current metadata, so it can refresh a token:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ClientSdksSamples.kt#sdk-metadata -->
```kotlin
fun authorized(fetcher: Fetcher, accessToken: suspend () -> String): Fetcher =
    fetcher.withMetadata { set("Authorization", "Bearer ${accessToken()}") }
```

The generated `Api` has the same method (`api.withMetadata { ... }`), returning a new client.  Metadata goes out as
headers over HTTP and as query parameters over WebSockets, since sockets can't carry custom headers.
`withHeaderCalculator` is deprecated in favor of `withMetadata`.

## Catching breaking changes

Clients compiled against an older SDK break when an endpoint's types change.  Commit a baseline of the API contract and
diff against it in CI:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ClientSdksSamples.kt#sdk-contract -->
```kotlin
fun writeApiBaseline(baseline: KFile) {
    baseline.writeString(
        apiBaselineJson.encodeToString(
            LightningServerKSchema.serializer(),
            SdkServer.lightningServerKSchemaFromDefaultRuntime.sorted()
        )
    )
}

fun checkApiAgainstBaseline(baseline: KFile): Boolean {
    val old = apiBaselineJson.decodeFromString(LightningServerKSchema.serializer(), baseline.readString())
    val report = diffApiContract(old, SdkServer.lightningServerKSchemaFromDefaultRuntime, ApiAllowlist.EMPTY)
    println(report.render(strict = false))
    return !report.hasFailures(strict = false)
}
```

The report separates breaking changes (failures) from potentially breaking ones, which fail only with `strict = true`.
Intended breaks can be listed in an `ApiAllowlist`.

## Calling the API directly

Clients without an SDK follow the same conventions the SDKs use:

- **Formats.** Send `Content-Type` and `Accept`.  A server with the basic coders accepts `application/json` (the default),
  `application/x-www-form-urlencoded`, and `application/x-lightningserver-kotlin-wire` (or the older
  `application/x-lightningserver-kotlin-bytes`); see
  [Serialization](serialization.md).
- **GET input** is the input object's fields as query parameters: `GET /products?text=lamp&limit=5`.
- **Authentication.** `Authorization: Bearer <token>`.  Where headers aren't possible (browser WebSockets), the access
  token may go in an `Authorization` or `jwt` query parameter; only WebSocket connects accept that.  Getting a token is covered in [Authentication](auth.md).
- **Errors** are JSON `LSError` bodies (`http`, `detail`, `message`, `data`); branch on `detail`.  See
  [Error handling](error-handling.md#the-response-body).
- **Database queries** to [Model REST](model-rest.md) endpoints take `Condition` and `Modification` JSON.
- **The contract** is published at `/meta/openapi.json` when `MetaEndpoints` is mounted; see [Meta and admin](meta-admin.md).

## Next

- [Typed endpoints](typed-endpoints.md): the declarations SDKs are generated from.
- [Meta and admin](meta-admin.md): OpenAPI, the docs UI, and live SDK downloads.
- [WebSockets](websockets.md): typed sockets.
