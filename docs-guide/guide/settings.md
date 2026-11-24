# Settings

Settings are a server's typed configuration: each `setting(...)` in a `ServerBuilder` becomes one key of
`settings.json`, read once at startup.  This chapter covers declaring settings, the file and how it loads, secrets,
and every setting the framework itself adds.  For service settings (database, cache, ...) and switching their
backends, see [Services](services.md).

<!-- sample: com/lightningkite/lightningserver/guide/samples/SettingsSamples.kt#settings-imports -->
```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.settings.*
import kotlinx.serialization.Serializable
import kotlin.test.*
```

## Declaring settings

<!-- sample: com/lightningkite/lightningserver/guide/samples/SettingsSamples.kt#settings-server -->
```kotlin
@Serializable
data class FeatureFlags(val newCheckout: Boolean = false, val maxCartItems: Int = 50)

object StoreSettingsServer : ServerBuilder() {
    val supportEmail = setting("supportEmail", "support@example.com")
    val featureFlags = setting("featureFlags", FeatureFlags())
    val maintenanceMessage = setting<String?>("maintenanceMessage", null, optional = true)
    val uploadLimitBytes = setting("uploadLimitMegabytes", 10) { megabytes -> megabytes * 1024L * 1024L }

    val status = path.path("status").get bind HttpHandler {
        HttpResponse.plainText(maintenanceMessage() ?: "Open. Questions? ${supportEmail()}")
    }
}
```

- The first argument is the key in the file; the default sets the type and the value a generated file starts with.
  Any `@Serializable` type works.
- `optional = true` lets the key be missing from the file; the default is used.  Required keys must be present.
- A trailing lambda transforms the stored value into what `uploadLimitBytes()` returns.  It runs once, when the
  runtime starts.
- Call the setting (`supportEmail()`) to read it, anywhere a runtime is in context.

Choose defaults that work locally without any setup: the generated file should run as-is.

The settings above produce this part of `settings.json` (keys are written sorted):

```json
{
  "featureFlags": { "newCheckout": false, "maxCartItems": 50 },
  "maintenanceMessage": null,
  "supportEmail": "support@example.com",
  "uploadLimitMegabytes": 10
}
```

## Loading the file

An engine loads the file before it starts:

```kotlin
fun main() {
    KtorEngine(Server.build()).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        start(Netty)
    }
}
```

`loadFromFile` (in `com.lightningkite.lightningserver.settings`) behaves like this:

| File state | Result |
|---|---|
| Missing | Writes the file with every key at its default, then throws `MissingSettingFile`.  Run again. |
| Missing required keys | Writes `settings.suggested.json` (the file plus the missing keys at their defaults) and throws `IncompleteSettingsException` naming the keys.  Copy them over. |
| Complete | Loads.  When the engine starts, every setting is built; any failure (a bad URL, an unregistered backend) stops startup with the setting's name logged. |

The file holds the server's settings plus the framework's (`secretBasis`, `general`, `telemetry`, `logging`, and the
engine's; see the [reference](#framework-settings)).

!!! warning
    Unknown keys are ignored, so a typo'd key is silently skipped: the setting takes its default if optional, or is
    reported missing if required.

## Settings in tests

`testBlocking` uses defaults for everything; its `settings` block overrides what a test needs:

<!-- sample: com/lightningkite/lightningserver/guide/samples/SettingsSamples.kt#settings-test -->
```kotlin
fun shopSettingsTest() = StoreSettingsServer.testBlocking(settings = {
    maintenanceMessage set "Back at noon"
    uploadLimitBytes set 2
}) {
    assertEquals("Back at noon", status.test().body!!.text())
    assertEquals(2L * 1024 * 1024, uploadLimitBytes())
    assertEquals(FeatureFlags(), featureFlags())
}
```

`set` takes the stored type (megabytes here); the transform still runs.  `setStatic` sets the result directly
(see [Services](services.md#services-in-tests)).

## File formats

JSON is the default and is parsed leniently.  A file whose extension is `.properties` is read as Java properties, with
dotted keys for nested values and `#` comments on their own lines:

```properties
# settings.properties
supportEmail=help@shop.example
featureFlags.newCheckout=true
featureFlags.maxCartItems=10
uploadLimitMegabytes=25
```

## Layering files with `defaults`

A file can name a lower-priority file in the special `defaults` key.  Its own keys win:

```json
{
  "defaults": "settings.base.json",
  "database": "mongodb://prod-db.example.com/shop"
}
```

The path is relative to the file that names it, `~` expands to the home directory, the defaults file may have its own
`defaults` (cycles are reported), and it may be JSON or properties.  A common split: commit `settings.base.json` with
everything shared, and keep secrets in an uncommitted `settings.json` that names it.

## Secrets

### `secretBasis`

`secretBasis` is the server's master key.  Sessions, signed tokens, proofs, and `SecretBasis.cipher`/`signer` keys
are derived from it (see [Encryption](encryption.md)).  Its value is the Base64 of 64 random bytes:

```bash
openssl rand -base64 64 | tr -d '\n'
```

```json
{ "secretBasis": "<88-character base64 string>" }
```

A value that decodes to fewer than 64 bytes fails at startup; bytes past the first 64 are ignored.

The generated file gets a fresh random value.  Treat it like a private key: never commit it, and know that changing it
invalidates every session and anything signed or encrypted with it.  Apps can declare their own with
`setting("someSecret", SecretBasis())`; the format and the random default are the same.

### Encrypted settings files

Encrypt the whole file with OpenSSL:

```bash
openssl enc -aes-256-cbc -pbkdf2 -in settings.json -out settings.json.enc
```

Then point `loadFromFile` at `settings.json.enc` and set `LIGHTNING_SERVER_SETTINGS_DECRYPTION` to the password.
Both `-pbkdf2` (10,000 iterations, the OpenSSL default) and legacy `-md sha256` files are recognized.  While that
variable is set, *every* file `loadFromFile` reads is decrypted, so a plain file then fails to load.

### AWS Lambda

The serverless engine loads settings itself: from the Secrets Manager secret named by
`LIGHTNING_SERVER_SETTINGS_SECRET_ID`, from `settings.json` or `settings.enc` in the deployment package, or from
the S3 object named by `LIGHTNING_SERVER_SETTINGS_BUCKET` and `LIGHTNING_SERVER_SETTINGS_FILE`, decrypting with
`LIGHTNING_SERVER_SETTINGS_DECRYPTION` when set.  `defaults` is not supported there.  See
[AWS Deployment](aws-deployment.md).

### Deploy-time secrets

Terraform builders ask a `SecretSource` (in `com.lightningkite.lightningserver.terraform`) for the values they can't
generate, such as cloud credentials.  The built-in builders try, in order, environment variables named
`LS_SECRET_<NAME>` (`EnvironmentSecretSource`) and then password-protected files under `~/.lightningserver/`
(`EncryptedFileSecretSource`), prompting for anything missing and saving it.  To keep them in AWS Secrets Manager
instead, add the `secret-source-aws` module and override the source:

```kotlin
override val secretsSource: SecretSource =
    AwsSecretSource(profile = "my-profile", idPrefix = "shop-prod", region = Region.US_WEST_2)
```

Each secret is stored as `<idPrefix>/<name>`.  `ManySecretSources(a, b, ...)` chains sources, returning the first
that has a value.  See [Terraform](terraform.md).

## Reference

### Value formats

| Type | JSON |
|---|---|
| `kotlin.time.Duration` | ISO-8601 string: `"PT30S"`, `"PT2M"`, `"PT1H"` |
| `DataSize` | number of bytes: `16777216` for 16 MiB |
| Service settings (`Database.Settings`, `Cache.Settings`, `PubSub.Settings`, ...) | URL string: `"ram"`, `"redis://host:6379"` |
| `TelemetryBackend.Settings` | object with `url` and batching options: `{"url": "noop", ...}` |
| `SecretBasis` | Base64 string |

### Framework settings

| Key | Present with | Required | Sample |
|---|---|---|---|
| `secretBasis` | every engine | yes | `"<base64 of 64 random bytes>"` |
| `general` | every engine | yes | `{"projectName": "Shop", "publicUrl": "https://api.shop.example", "wsUrl": "wss://api.shop.example", "debug": false}` |
| `telemetry` | every engine | yes | `{"url": "noop"}` |
| `logging` | every engine | yes | `{"default": {"filePattern": null, "toConsole": true, "level": "INFO", "additive": false}}` |
| `engine-cache` | Ktor, Netty, JDK | yes | `"ram"` |
| `engine-pubSub` | Ktor, Netty, JDK | yes | `"local"` |
| `webSocketSettings` | Ktor, Netty (the JDK engine has no WebSockets) | no | `{"ping": "PT30S", "pongTimeout": "PT15S", "maxFrameSize": 16777216}` |
| `forceWebSocketPubSub` | Ktor, Netty, JDK | no | `false` |
| `ktorRunConfig` | `KtorEngine` | yes | `{"host": "0.0.0.0", "port": 8080}` |
| `nettyRunConfig` | `NettyEngine` | yes | `{"host": "0.0.0.0", "port": 8080}` |
| `jdkRunConfig` | `JdkEngine` | yes | `{"host": "0.0.0.0", "port": 8080}` |
| `awsApiGatewayWsEndpointSetting` | AWS serverless | yes | `"wss://<api-id>.execute-api.<region>.amazonaws.com/<stage>"` (Terraform fills it) |

#### `general`

| Field | Default | Meaning |
|---|---|---|
| `projectName` | `"My Project"` | Display name. |
| `publicUrl` | `"http://localhost:8080"` | Base for absolute URLs (links in emails, redirects, `fullUrl()`).  No trailing slash. |
| `wsUrl` | `publicUrl` with `http` replaced by `ws` | Base for WebSocket URLs. |
| `debug` | `false` | When true, 500 responses include the exception and stack trace.  Never in production. |
| `emergencyContact` | `null` | Informational; nothing at runtime reads it.  The AWS Terraform builder fills it in. |

`wsUrl` is derived only when the key is absent.  A generated file writes it out, so if you later change `publicUrl`,
change `wsUrl` too.

#### `logging`

`default` configures the root logger; `logger` maps a logger name (usually a package) to its own entry.  Each entry
has `filePattern` (a Logback file pattern, or `null` for no file), `toConsole`, `level` (`TRACE` to `ERROR`), and
`additive`.

```json
{
  "logging": {
    "default": { "filePattern": null, "toConsole": true, "level": "INFO", "additive": false },
    "logger": { "com.example.shop": { "filePattern": null, "toConsole": true, "level": "DEBUG" } }
  }
}
```

Fields left out of a `logger` entry take the entry type's defaults, which differ from the `default` entry's:
`filePattern` is `"local/logs/logfile-%d{yyyy-MM-dd}.log"` and `toConsole` is `false`.  Spell them out.

#### `telemetry`

Service Abstractions' `TelemetryBackend.Settings`; `"noop"` sends nothing.  Backends and options:
[Telemetry URLs](https://github.com/lightningkite/service-abstractions/blob/main/docs/settings-url-reference.md#telemetry).
See [Observability](observability.md).

#### `engine-cache` and `engine-pubSub`

The engine's own cache (locks so each schedule runs on one instance) and pub/sub (delivering WebSocket topic
messages).  With the defaults `ram` and `local`, both work only within one process; a multi-instance deployment needs
shared backends.  Point them at your own settings with `bind` ([Services](services.md#sharing-one-backend-with-the-engine))
and the keys leave the file.

#### `webSocketSettings` and `forceWebSocketPubSub`

| Field | Default | Meaning |
|---|---|---|
| `ping` | `PT30S` | Ping interval; `null` disables.  Ktor and Netty. |
| `pongTimeout` | `PT15S` | Close with 1011 if a ping isn't answered in this time.  Ktor and Netty. |
| `maxFrameSize` | `16777216` | Largest frame in bytes; `null` for no limit.  Ktor and Netty. |

`forceWebSocketPubSub: true` routes WebSocket handlers through pub/sub even when they could run directly, as on
serverless; useful for testing that path locally.  The JDK engine has no WebSocket support.

#### Run configs

`ktorRunConfig`, `nettyRunConfig`, and `jdkRunConfig` share these fields:

| Field | Default | Meaning |
|---|---|---|
| `host` | `"0.0.0.0"` | Bind address. |
| `port` | `8080` | Bind port. |
| `realIpHeader` | `null` | Header to read the client IP from, such as `X-Forwarded-For`.  The right-most comma-separated entry is used (the one your proxy appended); set it only behind a proxy. |
| `requestIdHeader` | `null` | Header carrying a request ID from a trusted proxy (must be a UUIDv7); `null` always generates one. |
| `reliability` | see below | Limits and timeouts. |

`nettyRunConfig` adds: `workerThreads` (`null` for Netty's default), `maxAggregatedContentLength` (`16777216`, the
request body limit on Netty), `webSocketCompression` (`false`), `backlog` (`4096`), `recvBufBytes` and `sendBufBytes`
(`null` for the OS default), and `autoRead` (`true`).

`reliability`:

| Field | Default | Engines | Meaning |
|---|---|---|---|
| `maxBodySize` | `16777216` | Ktor, JDK | Larger request bodies get 413. |
| `idleTimeout` | `PT2M` | Netty | Close idle keep-alive connections. |
| `shutdownDrainTimeout` | `PT25S` | all | How long shutdown waits for in-flight work. |
| `webSocketInboundBuffer` | `256` | Ktor, Netty | Inbound frames buffered per socket. |
| `webSocketOversizePolicy` | `"CLOSE"` | Ktor, Netty | When the buffer is full: `CLOSE` (code 1009), `DROP_OLDEST`, or `SUSPEND`. |
| `workerThreads` | `null` | JDK | Request threads; `null` is twice the processor count. |
| `scheduleLockTtl` | `PT1H` | all | Lock expiry for a running schedule; one that runs longer may start again elsewhere. |

See [Engines](engines.md) for choosing and tuning an engine.

### Setting types modules provide

These are declared by your server, under keys you choose:

| Type (module) | Usual key | JSON | Chapter |
|---|---|---|---|
| `CorsSettings` (core) | `cors` | `{"limitToDomains": ["https://app.example.com"], "limitToHeaders": ["*"], "limitToMethods": ["*"], "exposedHeaders": [], "allowCredentials": true, "cacheLength": "PT10S", "forbidOnMatchFail": true}` | [CORS & Interceptors](cors-interceptors.md) |
| `RateLimitSettings` (ratelimit) | `rateLimit` | `{"headerPrefix": "X-RateLimit-", "rateLimiterId": "general", "includeHeaders": true}` | [Rate Limiting](rate-limiting.md) |
| `OauthProviderCredentials` (sessions-oauth) | `googleOAuth`, `githubOAuth`, ... | `{"id": "<client-id>", "secret": "<client-secret>"}` | [OAuth](oauth.md) |
| `OauthProviderCredentialsApple` (sessions-oauth) | `appleOAuth` | `{"serviceId": "...", "teamId": "...", "keyId": "...", "keyString": "<.p8 contents>"}` | [OAuth](oauth.md) |
| `OidcSigningKey` (sessions-oauth) | `oidcSigningKey` | `"<base64 PKCS#8 RSA private key>"` | [OAuth authorization server](oauth-provider.md) |
| `SecretBasis` (core) | your choice | `"<base64 of 64 random bytes>"` | [Encryption](encryption.md) |

Modules can declare settings of their own: mounting `ClientTelemetryEndpoints` adds `clientTelemetryRateLimit`
(a `RateLimitSettings`, or `null` to turn its limit off).

## Next

- [Services](services.md): service settings and backends.
- [Running](running.md): starting an engine.
- [Terraform](terraform.md): how deployments generate settings.
