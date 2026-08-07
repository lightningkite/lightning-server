> # ⚠️ UNREVIEWED FIRST DRAFT — DO NOT PUBLISH
> Auto-generated first pass. Code samples are modeled on the current source but have **NOT** been compiled, drift-checked, or reviewed. This page is intentionally **not** in the site nav. Before publishing: port samples into the drift-checked `docs-guide/src/samples` module, run the fresh-eyes judge, and delete this banner.

# Generating & Using Client SDKs

Every typed endpoint you declare participates in SDK generation.  Lightning
Server reads your server definition at generation time — without starting a
server, making network calls, or touching a database — and writes a complete,
type-safe client library in your target language.

Two generators ship with the framework:

- **`FetcherSdk`** — Kotlin/Multiplatform client (suspend functions,
  kotlinx.serialization)
- **`TypescriptFetcherSdk`** — TypeScript client (`@lightningkite/lightning-server-simplified`)

Both produce a matching pair of files: an interface (the API contract) and a
live implementation (the concrete class that makes real HTTP calls).  The
generated SDK always matches what your server actually serves because the
generator introspects the `ServerBuilder` directly — there is no separate
schema file to keep in sync.

## Imports

The SDK generation code lives in the `typed` module:

```kotlin
import com.lightningkite.lightningserver.typed.sdk.*
import com.lightningkite.services.kfile.KFile
import java.util.zip.ZipOutputStream
```

## Marking Modules for the SDK

By default, `path.path("foo") include SomeEndpoints` includes `SomeEndpoints`
in the server routing but *not* as its own named namespace in the generated
SDK — its endpoints appear flat in the root module.

To give a group of endpoints its own SDK interface and class, mount it with
`module` instead of `include`:

```kotlin
object Server : ServerBuilder() {
    // "module" marks BlogEndpoints as a separate SDK module.
    // The name is inferred from the class: "BlogEndpoints" → interface "BlogApi", value "blog".
    val blog = path.path("blog") module BlogEndpoints

    // You can also supply an explicit name.
    val users = path.path("user") module UserEndpoints.withSdkInfo("UserApi", "users")
}
```

`withSdkInfo(interfaceName, valueName)` is an extension on `ServerBuilder`.
When the generator sees a `module` mount, it emits a nested interface for that
group and a property of that interface type on the parent.  `include` endpoints
end up as flat functions on whichever module they belong to.

The naming rule when you omit `withSdkInfo`:

| Class name | Generated interface | Generated property |
|---|---|---|
| `BlogEndpoints` | `BlogApi` | `blog` |
| `UserModule` | `UserApi` | `user` |
| `CacheExamplesEndpoints` | `CacheExamplesApi` | `cacheExamples` |

`Endpoints` and `Module` suffixes are stripped; `Api` is appended to the
interface name; the property name is the camelCase interface name with the
`Api` suffix removed.

## Running Generation from Code

SDK generation does not require a running server.  Call
`writeUsingDefaultSettings`, which spins up a temporary, offline runtime with
all settings initialized to their defaults (no ports, no external services):

```kotlin
// From demo/src/main/kotlin/.../main.kt (verified against source).
fun sdk() {
    // Kotlin/Multiplatform SDK — writes two files:
    //   Api.kt  (interface)
    //   LiveApi.kt  (implementation)
    FetcherSdk("com.lightningkite.lightningserver.demo").writeUsingDefaultSettings(
        Server,
        KFile("demo/src/main/kotlin/sdk")
    )
}
```

For TypeScript:

```kotlin
fun sdk() {
    TypescriptFetcherSdk().writeUsingDefaultSettings(
        Server,
        KFile("output/sdk/typescript")
    )
    // Writes: models.ts, Api.ts, LiveApi.ts
}
```

Wire this as a CLI subcommand:

```kotlin
fun main(vararg args: String) {
    when (args.firstOrNull()) {
        "sdk" -> sdk()
        else -> serve()
    }
}
```

Then run:

```
./gradlew :your-module:run --args="sdk"
```

## Configuring the Generators

### `FetcherSdk`

```kotlin
// Signature (from typed/src/main/kotlin/.../sdk/FetcherSdk.kt):
FetcherSdk(
    packageName: String,                       // required — package for generated .kt files
    rootInfo: SdkModule.Info = SdkModule.Info("Api"),
    fileStructure: FetcherSdk.Structure = Structure.MultipleFiles(
        interfaceFilename = "${rootInfo.interfaceName}.kt",
        liveFilename    = "Live${rootInfo.interfaceName}.kt"
    ),
    includeDocComments: Boolean = true,
)
```

`FetcherSdk.Structure` has two options:

- `Structure.MultipleFiles(interfaceFilename, liveFilename)` — default; one file
  for the interface, one for the live class.
- `Structure.SingleFile(filename)` — both in one file.

### `TypescriptFetcherSdk`

```kotlin
// Signature (from typed/src/main/kotlin/.../sdk/TypescriptFetcherSdk.kt):
TypescriptFetcherSdk(
    rootInfo: SdkModule.Info = SdkModule.Info("Api"),
    fileStructure: TypescriptFetcherSdk.Structure = Structure.MultipleFiles(
        modelsFilename    = "models.ts",
        interfaceFilename = "${rootInfo.interfaceName}.ts",
        liveFilename      = "Live${rootInfo.interfaceName}.ts"
    ),
    includeDocComments: Boolean = true,
    erasableTypes: Boolean = false,       // emit "type" aliases instead of "enum" for TS 5.5+ erasable types
)
```

`TypescriptFetcherSdk.Structure` has two options:

- `Structure.MultipleFiles(modelsFilename, interfaceFilename, liveFilename)` —
  default; models, interface, and live class in separate files.
- `Structure.SingleFile(filename)` — everything in one file.

## Writing to a ZIP Instead of a Folder

Both generators target any `Archive` implementation.  To produce a ZIP:

```kotlin
import java.io.FileOutputStream
import java.util.zip.ZipOutputStream

fun sdkZip() {
    val zipOut = ZipOutputStream(FileOutputStream("sdk.zip"))
    Archive.zip(zipOut).use { archive ->
        // write into a "sdk/" subdirectory inside the ZIP
        TypescriptFetcherSdk().writeUsingDefaultSettings(Server, archive.sub("sdk"))
    }
}
```

`Archive` has three factory methods:

| Method | Output |
|---|---|
| `Archive.folder(KFile)` | writes real files to a directory |
| `Archive.zip(ZipOutputStream)` | writes ZIP entries |
| `Archive.singleStream(Sink)` | concatenates all files into one stream |

`archive.sub("name")` creates a logical subdirectory; `archive.entry("file.kt") { sink -> ... }` writes a single file.

## Providing a Custom Root Name

The `rootInfo` parameter controls the top-level generated names:

```kotlin
FetcherSdk(
    packageName = "com.example.client",
    rootInfo = SdkModule.Info("MyProjectApi"),   // interface MyProjectApi, class LiveMyProjectApi
)
```

## What the Generated Kotlin SDK Looks Like

For a server with a `POST /divide` endpoint whose `summary` is `"Divide two
numbers"`, `FetcherSdk` emits (illustrative, based on `FetcherSdk.kt`):

```kotlin
// Api.kt
interface Api {
    /**
     * Divide two numbers
     * ...
     * **Auth Requirements:** No authentication required
     */
    suspend fun divideTwoNumbers(input: DivideRequest): DivideResponse
}

// LiveApi.kt
@OptIn(ExperimentalSerializationApi::class)
class LiveApi(val fetcher: Fetcher) : Api {
    override suspend fun divideTwoNumbers(input: DivideRequest): DivideResponse =
        fetcher("/divide", HttpMethod.POST, DivideRequest.serializer(), input, DivideResponse.serializer())
}
```

The function name is derived from `summary` converted to camelCase
(`"Divide two numbers"` → `divideTwoNumbers`).

## What the Generated TypeScript SDK Looks Like

For the same endpoint, `TypescriptFetcherSdk` emits (illustrative):

```typescript
// Api.ts
export interface Api {
  /**
   * Divide two numbers
   * ...
   */
  divideTwoNumbers(input: DivideRequest): Promise<DivideResponse>
}

// LiveApi.ts
export class LiveApi implements Api {
  public fetcher: Fetcher
  public constructor(fetcher: Fetcher) {
    this.fetcher = fetcher
  }

  divideTwoNumbers: Api["divideTwoNumbers"] = (input) =>
    this.fetcher(`/divide`, "POST", input)
}
```

## Kotlin Type → TypeScript Type Mapping

`TypescriptFetcherSdk` applies this mapping automatically:

| Kotlin | TypeScript |
|---|---|
| `String`, `Char` | `string` |
| `Int`, `Long`, `Float`, `Double` | `number` |
| `Boolean` | `boolean` |
| `List<T>` | `Array<T>` |
| `Map<String, V>` | `Record<string, V>` |
| `T?` | `T \| null \| undefined` |
| data class | `export interface` |
| `enum class` | `export enum` (or `export type` when `erasableTypes = true`) |
| `Unit` | `void` |

Types from the Lightning Server runtime (e.g., `Query`, `Condition`,
`Modification`) are imported from `@lightningkite/lightning-server-simplified`
rather than duplicated in the generated models file.

## API Contract Testing

After you commit a generated SDK baseline, you can diff it in CI to catch
breaking changes.  The demo shows a companion pattern:

```kotlin
// From demo/.../main.kt:
fun apiBaselineWrite(out: File = File("api-baseline.json")) { ... }
fun apiCheck(baseline: File = File("api-baseline.json"), strict: Boolean = false) { ... }
```

`apiBaselineWrite` serializes the current API schema to a JSON file.
`apiCheck` diffs the current schema against the baseline and calls
`exitProcess(1)` on breaking changes.  Running this in CI before shipping
prevents silent contract breaks.

## Live SDK Download (Runtime)

In addition to build-time generation, you can expose the SDK for download
directly from your running server.  This is covered in
[Meta, OpenAPI & Admin Panel](meta-admin.md) — the `MetaEndpoints` block
mounts `ApiDocs` at `/meta/docs`, which serves:

- `/meta/docs/sdk.ts` — full TypeScript SDK as a single file
- `/meta/docs/sdk.ts.zip` — TypeScript SDK as a multi-file ZIP
- `/meta/docs/sdk.kt` — full Kotlin SDK as a single file
- `/meta/docs/sdk.kt.zip` — Kotlin SDK as a multi-file ZIP

During development this is the fastest way to get an up-to-date SDK into a
client project.

## What's Next

- **Meta, OpenAPI & Admin Panel** — mount `MetaEndpoints` to get the live SDK
  download, Swagger UI, health checks, and the admin panel all for free.
- **Authentication** — `auth` on each `ApiHttpHandler` is reflected in the
  generated SDK, so client code can see which calls need a session token.
- **WebSocket support** — `ApiWebsocketHandler` endpoints appear in the Kotlin
  SDK as `fun name(): ClientWebSocket<In, Out>` (TypeScript WebSocket generation
  is marked as not yet implemented in the current source).
