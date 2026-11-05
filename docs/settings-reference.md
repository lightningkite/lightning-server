# Settings Reference

The JSON shape of every setting that Lightning Server defines itself, taken from the code, not from KDoc.  Service Abstractions settings (`Database.Settings`, `Cache.Settings`, `TelemetryBackend.Settings`, ...) are URL strings; they're covered in service-abstractions' `docs/settings-url-reference.md`.  For how settings files load, chain, and encrypt, see [settings.md](settings.md).

## Cheatsheet

"Required" means the server refuses to start until the key is in the file, and writes `settings.suggested.json` with defaults.  Placeholders are in `<angle brackets>`.

### Keys the framework adds

| Key | Present when | Required | Sample |
|---|---|---|---|
| `secretBasis` | always | yes | `"<88-char base64 of 64 random bytes>"` |
| `general` | always | yes | `{"projectName": "<name>", "publicUrl": "https://<host>", "wsUrl": "wss://<host>", "debug": false}` |
| `telemetry` | always | yes | `{"url": "noop"}` (Service Abstractions `TelemetryBackend.Settings`) |
| `logging` | always | yes | `{"default": {"filePattern": null, "toConsole": true, "level": "INFO", "additive": false}}` |
| `engine-pubSub` | Ktor / Netty / JDK engine | yes | `"local"` or `"redis://<host>:<port>"` (Service Abstractions `PubSub.Settings`) |
| `engine-cache` | Ktor / Netty / JDK engine | yes | `"ram"` or `"redis://<host>:<port>"` (Service Abstractions `Cache.Settings`) |
| `webSocketSettings` | Ktor / Netty / JDK engine | no | `{"ping": "PT30S", "pongTimeout": "PT15S", "maxFrameSize": 16777216}` |
| `forceWebSocketPubSub` | Ktor / Netty / JDK engine | no | `false` |
| `nettyRunConfig` | `NettyEngine` | yes | `{"host": "0.0.0.0", "port": <8080>, "realIpHeader": "<X-Forwarded-For>", "reliability": {...}}` |
| `ktorRunConfig` | `KtorEngine` | yes | `{"host": "0.0.0.0", "port": <8080>, "realIpHeader": "<X-Forwarded-For>", "reliability": {...}}` |
| `jdkRunConfig` | `JdkEngine` | yes | `{"host": "0.0.0.0", "port": <8080>, "reliability": {"workerThreads": <n>}}` |
| `awsApiGatewayWsEndpointSetting` | AWS serverless | yes (Terraform fills it) | `"wss://<api-id>.execute-api.<region>.amazonaws.com/<stage>"` |

`reliability`, inside each run config: `{"maxBodySize": 16777216, "idleTimeout": "PT2M", "shutdownDrainTimeout": "PT25S", "webSocketInboundBuffer": 256, "webSocketOversizePolicy": "CLOSE", "workerThreads": null, "scheduleLockTtl": "PT1H"}`.  Each engine ignores some of these fields; see [EngineReliabilitySettings](#enginereliabilitysettings-not-a-standalone-setting).

### Types apps declare with `setting("<key>", ...)`

| Type (module) | Typical key | Sample |
|---|---|---|
| `CorsSettings` (core) | `cors` | `{"limitToDomains": ["https://<app-host>"], "limitToHeaders": ["*"], "limitToMethods": ["*"], "allowCredentials": true, "cacheLength": "PT10S"}` |
| `RateLimitSettings` (ratelimit) | `rateLimit` | `{"headerPrefix": "X-RateLimit-", "rateLimiterId": "<id>", "includeHeaders": true}` (limits themselves are set in code) |
| `OauthProviderCredentials` (sessions-oauth) | `googleOAuth`, `githubOAuth`, `microsoftOAuth`, ... | `{"id": "<client-id>", "secret": "<client-secret>"}` |
| `OauthProviderCredentialsApple` (sessions-oauth) | `appleOAuth` | `{"serviceId": "<service-id>", "teamId": "<team-id>", "keyId": "<key-id>", "keyString": "<.p8 base64 body>"}` |
| `SecretBasis` (core) | `<moduleSecret>` | `"<88-char base64 of 64 random bytes>"` |

### Value formats

- `Duration` is an ISO-8601 string: `"PT30S"`, `"PT2M"`, `"PT1H"`.
- `DataSize` is a plain number of bytes: `16777216` for 16 MiB.
- Service Abstractions `XxxService.Settings` value classes are bare URL strings.  `TelemetryBackend.Settings` is the exception: an object with `url`.
- Generate a `SecretBasis` with `openssl rand -base64 64 | tr -d '\n'`.

## Common mechanics

- Settings file = one JSON object, one key per `ServerSetting.name`, plus optional `defaults` (path of a lower-priority file; `~` expanded; disabled in AWS Lambda) (`core/settings/SettingsSerializer.kt:57-65,117-142`).
- Json config: `isLenient`, `ignoreUnknownKeys`, `prettyPrint`, `encodeDefaults` (`core/settings/ServerSettings.ext.kt:~184-190`). A typo'd key is silently ignored (default used, or "missing" error if non-optional).
- Key absent from the file and `optional = false` -> `IncompleteSettingsException`, and a `*.suggested.json` is written with defaults (`ServerSettings.ext.kt:135-146`). Keys with `optional = true` silently take the default. File missing -> written with all defaults, then `MissingSettingFile` thrown (`:119-122`).
- Only env var that touches the file: `LIGHTNING_SERVER_SETTINGS_DECRYPTION` (OpenSSL AES-CBC password for the whole file, `:125`). There are **no** per-setting env overrides for any setting below. (`LS_SECRET_<name>` in `core/terraform/SecretSource.kt:381` is deploy-time Terraform only; not checked for these.)
- `kotlin.time.Duration` serializes as an ISO-8601 string (`"PT30S"`, `"PT2M"`, `"PT1H"`). No custom Duration serializer found in core or service-abstractions.
- `DataSize` (`service-abstractions/data-shared/.../usefulTypes.kt:118-120`) is `@Serializable @JvmInline value class DataSize(val bytes: Long)`, no custom serializer -> a bare JSON number of **bytes**, e.g. `16777216` for 16 MiB.
- `PubSub.Settings` and `Cache.Settings` are `@JvmInline value class(val url: String)` -> bare JSON **string**, not `{"url":..}`. `TelemetryBackend.Settings` is a regular data class -> JSON object with `url`.
- `.properties` settings files also work (`ServerSettings.ext.kt:151-180`); examples here are JSON.

## Settings the framework registers

### When the globals are registered

`secretBasis`, `general`, `telemetry`, `logging` are added **unconditionally** by `EngineBase` (`core/runtime/EngineBase.kt:22-27`: `ServerSettings(server) + setOf(generalSettings, secretBasis, telemetrySettings, loggingSettings)`). Every engine extends `EngineBase`, so they are always present and always required keys in the file, used or not. `ServerSettings.ready()` eagerly resolves every setting and applies `logging` to logback first (`core/settings/ServerSettings.kt:224-225`). `AwsAdapter` is an `EngineBase` directly (not `LocalEngine`), so it has none of the `engine-*` / `webSocketSettings` keys. `TerraformAwsServerlessBuilder.additionalSettings` also lists secretBasis/telemetry/logging (`engine-aws-serverless/.../terraform/awsserverless/TerraformAwsServerlessBuilder.kt:66-70`) and Terraform auto-fills `general` (`:210-225`).

`LocalEngine` (base of Ktor, Netty, JDK engines) adds `engine-pubSub`, `engine-cache`, `forceWebSocketPubSub`, `webSocketSettings` (`engine-local/.../LocalEngine.kt:120-125`); each engine class adds its own run-config.

### secretBasis

- `core/definition/globalSettings.kt:19-20`. `ServerSetting.Direct<SecretBasis>`. Always present, required.
- Kotlin: `@Serializable(SecretBasis.Serializer::class) data class SecretBasis(val string: String)` (`core/encryption/SecretBasis.kt:37-38`).
- JSON: a single **string** (Base64 of the master key), not an object (`:251-259`).

| field | type | default | meaning |
|---|---|---|---|
| (whole value) | string, Base64 | **random 64 bytes** generated when `SecretBasis()` runs (`:56-62`: HMAC-SHA512 keygen, RAW, Base64) | master secret; per-purpose keys derived via HMAC-SHA512 / HKDF (`derive`, `cipher`, `signer` in `SecretBasis*.kt`) |

Validation / derived:
- `bytes = Base64.decode(string).sliceArray(0 until 64)` (`:70-71`). Fewer than 64 decoded bytes -> raw slice exception at deserialize; **more** than 64 -> silently truncated to the first 64. The `require(bytes.size == BYTES)` at `:73-75` can never fail after that slice. Invalid Base64 -> exception from `Base64.decode`.
- 64 bytes = 88 Base64 chars with padding. `BASE64_CHARS = 66` (`:47`) does not match that; I found no reader of it.
- Terraform: `secretBasis.generated()` uses `random_password` length 88, `special=true`, `override_special="+/"` (`core/terraform/BaseTerraformEmitter.kt:431-446`): 88 chars -> 66 bytes, first 64 used.

Surprises:
- The default is random **per process** (evaluated once at class init). Missing key in the file -> `IncompleteSettingsException` and the suggested file contains a fresh random value. Under `readyUsingDefaults()` (tests) each process gets a different secret.
- Changing it invalidates everything encrypted/signed with it. Secret: never commit. No env override.

```json
{ "secretBasis": "<88-char base64 string>" }
```

Apps can also declare their own, e.g. `setting("moduleSecret", SecretBasis())`; same JSON format and the same random-default trap.

Purpose: master key for deriving per-purpose keys by variant string. `derive(variant)` = HMAC-SHA512(master key, variant bytes), optionally HKDF-expanded to a size (`:145-154`). `SecretBasis.ciphers.kt`: AES-GCM `cipher(variant)` (also CBC/CTR). `SecretBasis.signers.kt`: HS512 `signer(variant)` (also HS256/384). In-repo variant: `"proof"` for OAuth proof signing (`OauthProofEndpoints.kt:52`). Changing the value invalidates sessions, signed proofs and anything encrypted with it (`globalSettings.kt:16-17`).

Security: data class `toString()` includes `string` by declaration; would leak if the object is logged (not verified that anything logs it).

### general

- `core/definition/globalSettings.kt:30-31` (Kotlin `generalSettings`, JSON key **`general`**). `ServerSetting.Direct<GeneralServerSettings>`. Always present, required.
- Kotlin: `@Serializable data class GeneralServerSettings` (`core/definition/GeneralServerSettings.kt:24-32`).

| field | type | default | meaning |
|---|---|---|---|
| `projectName` | String | `"My Project"` | `Engine.projectName` (`EngineBase.kt:70`) |
| `publicUrl` | String | `"http://localhost:8080"` | base URL: `fullUrl()` (`core/pathing/ResolvedPath.kt:113,195,276`), redirect path adjustment (`core/shortcuts.kt`), CORS error text (`core/cors/CorsInterceptor.kt:88`), test request defaults. Not validated; trailing slash not rejected (KDoc says "should not include") |
| `wsUrl` | String | derived: `https...` -> `wss...`, else `http` replaced by `ws` (`:28-29`) | base for `wsFullUrl()`; AWS adapter uses it (`AwsAdapterWs.kt:34`) |
| `debug` | Boolean | `false` | Actual effect: 500 responses carry exception class, message and full stack trace (`core/http/HttpExceptionHandler.kt:63-77`); AWS WS "debug" query-param diagnostic (`AwsAdapterWs.kt:549`). KDoc says it "affects CORS": no code supports that (CorsInterceptor never reads `debug`) |
| `emergencyContact` | String? | `null` | only consumer found is Terraform (`TerraformAwsServerlessBuilder.kt:224`); no runtime reader found |

Derived, not serialized: `publicUrlDomain`, `wsUrlDomain` (`substringAfter("://").substringBefore("/")`), `absolutePathAdjustment(path)` (prefixes the path part of `publicUrl` onto absolute paths; `:38-67`).

Surprises:
- `wsUrl` is derived from `publicUrl` only when omitted. With `encodeDefaults=true` a generated/suggested file writes `wsUrl` explicitly, so later editing only `publicUrl` leaves a stale `wsUrl`.
- AWS: Terraform writes `general` itself; custom domain without CloudFront gives `wsUrl` ending `?path=` (`TerraformAwsServerlessBuilder.kt:215-219`).
- `debug: true` in production leaks stack traces.

```json
{ "general": { "projectName": "Acme API", "publicUrl": "https://api.acme.com", "wsUrl": "wss://api.acme.com", "debug": false, "emergencyContact": "ops@acme.com" } }
```

### telemetry

- `core/definition/globalSettings.kt:44-45`. `ServerSetting<TelemetryBackend.Settings, TelemetryBackend>` (not Direct; getter `{ it("telemetry", this) }`). Always present, required. Default `TelemetryBackend.Settings()` = `{"url":"noop"}` (no-op).
- JSON: object. See service-abstractions `docs/settings-url-reference.md#telemetry`.
- KDoc above it is stale: says default is null and type is `OpenTelemetrySettings` (deprecated); code uses `TelemetryBackend.Settings`.

```json
{ "telemetry": { "url": "noop" } }
```

### logging

- `core/definition/globalSettings.kt:55-56`. `ServerSetting.Direct<LoggingSettings>`. Always present, required. Default `LoggingSettings()` = `default: {filePattern: null, toConsole: true, level: INFO, additive: false}`, `logger: null` (`service-abstractions/basis/src/commonMain/kotlin/com/lightningkite/services/LoggingSettings.kt:62-65`). Applied to logback at `ready()` (`ServerSettings.kt:224-225`). Fields: see service-abstractions `LoggingSettings`.
- Gotcha: `ContextSettings` class defaults are `filePattern="local/logs/logfile-%d{yyyy-MM-dd}.log"`, `toConsole=false` (`:78-84`) but the top-level default overrides them. A `logger` entry that omits fields gets the class defaults (file on, console off).

```json
{ "logging": { "default": { "filePattern": null, "toConsole": true, "level": "INFO", "additive": false } } }
```

### webSocketSettings

- JSON key **`webSocketSettings`** (`core/websockets/WebsocketSettings.kt:35-40`). `ServerSetting.Direct<WebSocketSettings>`, `optional = true`. Registered by `LocalEngine` only (`LocalEngine.kt:124`): Ktor/Netty/JDK engines; absent for `AwsAdapter`.
- Kotlin: `@Serializable data class WebSocketSettings` (`:28-33`).

| field | type | default | meaning |
|---|---|---|---|
| `ping` | Duration? (ISO-8601) | `PT30S` | server ping period; `null` disables. Ktor `pingPeriodMillis` (`KtorEngine.kt:124`) |
| `pongTimeout` | Duration | `PT15S` | wait for ping send + pong before closing. Ktor `timeoutMillis` (`:125`) |
| `maxFrameSize` | DataSize? (bytes number) | `16777216` | max frame; `null` = unbounded. Ktor (`:127`); Netty handshaker, clamped to `Int.MAX_VALUE` (`NettyEngine.kt:524`) |

Surprises:
- **Netty reads only `maxFrameSize`**; `ping`/`pongTimeout` are not read in `engine-netty/src/main`. JDK engine has no WebSockets (501). Ktor uses all three.
- Optional: omitting the key silently applies defaults.

```json
{ "webSocketSettings": { "ping": "PT30S", "pongTimeout": "PT15S", "maxFrameSize": 16777216 } }
```

### engine-pubSub

- `engine-local/.../LocalEngine.kt:39-40`. `ServerSetting<PubSub.Settings, PubSub>`, required; Ktor/Netty/JDK. Default `"local"`.
- JSON: bare URL string. Schemes `local`, `debug`, `redis://`, `dynamodb-pubsub://`... (service-abstractions `docs/settings-url-reference.md#pubsub`; non-built-in schemes need their module preloaded). Carries WebSocket subscription messages (`LocalEngine.kt:130,162`).

```json
{ "engine-pubSub": "local" }
```

### engine-cache

- `LocalEngine.kt:46-47`. `ServerSetting<Cache.Settings, Cache>`, required, same presence. Default `"ram"`. Used for scheduled-task distributed locks (`"$name-lock"` with `scheduleLockTtl`, `:257`).
- JSON: bare URL string (`ram`, `redis://...`, `dynamodb://...`; `docs/settings-url-reference.md#cache`).
- Inference (untested): with `ram`, locks are per-process, so multi-instance deployments run each schedule on every instance.

```json
{ "engine-cache": "ram" }
```

### forceWebSocketPubSub

- `LocalEngine.kt:59-64`. `ServerSetting.Direct<Boolean>`, `optional = true`, default `false`. Local engines only.
- When `true`, handlers implementing `DirectExecutableWebSocketHandler` still go through pub/sub (mimics serverless). Read in `KtorEngine.kt:222`; engine-netty has no reference (unverified: NettyEngine may ignore it).

```json
{ "forceWebSocketPubSub": false }
```

### nettyRunConfig

- `engine-netty/.../NettyRuntimeSettings.kt:57-61`. `ServerSetting.Direct<NettyRuntimeSettings>`, required, registered by `NettyEngine` (`NettyEngine.kt:104`).
- Kotlin: `@Serializable data class NettyRuntimeSettings` (`:38-52`).

| field | type | default | meaning |
|---|---|---|---|
| `host` | String | `"0.0.0.0"` | bind address |
| `port` | Int | `8080` | bind port |
| `realIpHeader` | String? | `null` | header to read client IP from (trusted blindly: only set behind a proxy) |
| `requestIdHeader` | String? | `null` | request-id header from a trusted proxy (must be UUIDv7); `null` = always generate |
| `workerThreads` | Int? | `null` | worker event-loop threads; `null`/0 = Netty default (`NettyEngine.kt:183`); no validation of negatives |
| `maxAggregatedContentLength` | DataSize | `16777216` | HTTP aggregator body cap; clamped to `0..Int.MAX_VALUE`, warns above (`:216-220`) |
| `webSocketCompression` | Boolean | `false` | per-message deflate |
| `backlog` | DataSize | `4096` | `SO_BACKLOG` via `.bytes.toInt()` (a count, typed as DataSize) |
| `recvBufBytes` | DataSize? | `null` | `SO_RCVBUF`; null = OS default |
| `sendBufBytes` | DataSize? | `null` | `SO_SNDBUF` |
| `autoRead` | Boolean | `true` | channel auto-read |
| `reliability` | EngineReliabilitySettings | defaults | `maxBodySize`, `workerThreads` ignored here |

```json
{ "nettyRunConfig": { "host": "0.0.0.0", "port": 8080, "maxAggregatedContentLength": 16777216, "backlog": 4096, "reliability": { "idleTimeout": "PT2M" } } }
```

### ktorRunConfig

- `engine-ktor/.../KtorEngine.kt:73-77`. `ServerSetting.Direct<KtorRuntimeSettings>`, required, registered by `KtorEngine` (`:111`).
- Kotlin: `data class KtorRuntimeSettings` (`:61-68`).

| field | type | default | meaning |
|---|---|---|---|
| `host` | String | `"0.0.0.0"` | bind |
| `port` | Int | `8080` | bind |
| `realIpHeader` | String? | `null` | client IP header (`extensions.kt:103`) |
| `requestIdHeader` | String? | `null` | trusted-proxy request-id header |
| `reliability` | EngineReliabilitySettings | defaults | `idleTimeout`, `workerThreads` ignored |

The underlying Ktor engine factory (Netty/CIO/...) is chosen in code via `start(...)`, not in settings.

```json
{ "ktorRunConfig": { "host": "0.0.0.0", "port": 8080, "realIpHeader": "X-Forwarded-For" } }
```

### jdkRunConfig

- `engine-jdk-server/.../JdkEngine.kt:52-56`. `ServerSetting.Direct<JdkRuntimeSettings>`, required, registered by `JdkEngine` (`:80`).
- Kotlin: `data class JdkRuntimeSettings` (`:40-47`): `host` "0.0.0.0", `port` 8080, `realIpHeader` null, `requestIdHeader` null, `reliability` (`workerThreads` honored; `idleTimeout` ignored).
- JDK engine has no WebSocket support, so `webSocketSettings`, `forceWebSocketPubSub`, `webSocketInbound*` are registered but inert there.

```json
{ "jdkRunConfig": { "port": 8080, "reliability": { "workerThreads": 8 } } }
```

### EngineReliabilitySettings (not a standalone setting)

`engine-local/.../EngineReliabilitySettings.kt:61-70`. **Not** a `ServerSetting`; no key of its own. It is the `reliability` field nested in `nettyRunConfig`, `ktorRunConfig`, `jdkRunConfig`. Enum `WsOversizePolicy { CLOSE, DROP_OLDEST, SUSPEND }` (`:14-23`) serializes as its name.

| field (`reliability.`) | type | default | meaning |
|---|---|---|---|
| `maxBodySize` | DataSize | `16777216` | 413 above this; **Ktor and JDK only**; Netty ignores it (uses `maxAggregatedContentLength`) |
| `idleTimeout` | Duration | `PT2M` | idle keep-alive close; **Netty only** (`NettyEngine.kt:251`, truncated to whole seconds) |
| `shutdownDrainTimeout` | Duration | `PT25S` | graceful shutdown wait; all three |
| `webSocketInboundBuffer` | Int | `256` | inbound frame channel capacity, `coerceAtLeast(1)` (`LocalEngine.kt:300`) |
| `webSocketOversizePolicy` | enum | `CLOSE` | overflow behavior |
| `workerThreads` | Int? | `null` | **JDK only**: `null` -> `availableProcessors*2`, min 1 (`JdkEngine.kt:168`) |
| `scheduleLockTtl` | Duration | `PT1H` | schedule lock expiry backstop; longer-running schedules may start concurrently elsewhere |

Per-request timeout is not here (per-handler `HttpHandler.timeout`, KDoc `:34-37`).

### awsApiGatewayWsEndpointSetting

- Key `awsApiGatewayWsEndpointSetting` (`engine-aws-serverless/.../engine/awsserverless/AwsAdapter.kt:45`, registered `:52`). `private val` (not importable). `ServerSetting.Direct<String>`, default `""`, **required**. Only present in `AwsAdapter`.
- Meaning: API Gateway WebSocket management endpoint. Used at `:140`: strips `wss://`, prefixes `https://`, passed to `ApiGatewayManagementApiAsyncClient.endpointOverride` (lazy, so a bad value fails on first WS send, not startup; inference).
- Terraform fills it: `fulfillSetting("awsApiGatewayWsEndpointSetting", "${aws_apigatewayv2_stage.ws.invoke_url}")` (`TerraformAwsServerlessBuilder.kt:227`). Hand-written only for non-Terraform deploys.

```json
{ "awsApiGatewayWsEndpointSetting": "wss://abc123.execute-api.us-west-2.amazonaws.com/ws" }
```

### awsLambdaRuntimeSettings (commented out)

`engine-aws-serverless/.../AwsLambdaRuntimeSettings.kt:4-11` is entirely commented out. No such setting exists. `AwsAdapter` loads settings itself from Secrets Manager / `settings.json` / `settings.enc` in `LAMBDA_TASK_ROOT` / S3 (`AwsAdapter.kt:99-118`; env `LIGHTNING_SERVER_SETTINGS_SECRET_ID`, `_DECRYPTION`, `_BUCKET`, `_FILE`).

## Setting types apps declare

### CorsSettings
Module `core`, `com.lightningkite.lightningserver.cors.CorsSettings`, `core/src/main/kotlin/com/lightningkite/lightningserver/cors/CorsSettings.kt:49-58`. `@Serializable` data class. Consumed by `CorsInterceptor(Runtime<CorsSettings>)` (`cors/CorsInterceptor.kt:62`). Key is app-chosen (demo: `setting("cors", CorsSettings())`, `demo/.../Server.kt:76`).

| field | type | default | meaning |
|---|---|---|---|
| `limitToDomains` | `List<String>` | `[]` | Allowed origins. `[]` = none. Exactly `["*"]` = all (checked as `== listOf("*")`, `CorsInterceptor.kt:70`). Else patterns, below. |
| `limitToHeaders` | `List<String>` | `[]` | Joined with `,` into `Access-Control-Allow-Headers` (preflight only). Exactly `["*"]` = mirror `Access-Control-Request-Headers`. |
| `limitToMethods` | `List<String>` | `[]` | Preflight only. Exactly `["*"]` = every method the path implements (HEAD added if GET exists). Otherwise implemented methods are *filtered* to those named (compared to `HttpMethod.toString()`; use upper case). Not "placed directly" as KDoc says. |
| `exposedHeaders` | `List<String>` | `[]` | `Access-Control-Expose-Headers`, non-preflight responses only; omitted when empty. |
| `allowCredentials` | `Boolean` | `false` | Adds `Access-Control-Allow-Credentials: true`. |
| `cacheLength` | `Duration?` | `null` | `Access-Control-Max-Age` in whole seconds, preflight only. ISO string e.g. `"PT10S"`. |
| `forbidOnMatchFail` | `Boolean` | `true` | Non-matching Origin gives 403 for HTTP. `false` = request proceeds without CORS headers. WebSockets ignore it and always 403 (`CorsInterceptor.kt:182-185`). |

Origin matching (`originMatches`, `CorsInterceptor.kt:23-38`), case-insensitive, on `scheme://host[:port]`:
- Pattern containing `://` must match the scheme; a pattern without a scheme matches any scheme.
- Host part: `*` matches all; exact equality (port included); or a leading `*` is a suffix match, so `*.example.com` matches `a.example.com` but NOT `example.com`. `*example.com` (no dot) also matches `evilexample.com`. `https://*` allows any https origin.
- No `Origin` header: request passes untouched.
- Allowed: `Access-Control-Allow-Origin` echoes the request origin; `Vary: Origin` always added.

Presets (companion, `CorsSettings.kt:59-101`; Kotlin factories, the JSON is just the resulting object):

| preset | limitToDomains | limitToHeaders | limitToMethods | allowCredentials | cacheLength | forbidOnMatchFail |
|---|---|---|---|---|---|---|
| `CorsSettings()` | `[]` | `[]` | `[]` | false | null | true |
| `allowAll()` | `["*"]` | `["*"]` | `["*"]` | true | `PT10S` | **false** |
| `forProduction(vararg origins)` | the origins | `["*"]` | `["*"]` | true | `PT10S` | true |

(`exposedHeaders` is `[]` in all.)

Surprises:
- `CorsSettings()` allows nothing and rejects with 403, including the server's own public URL (error detail says to add it, `CorsInterceptor.kt:77-83`).
- `allowAll()` = reflected origin + credentials, no validation (TODO at `CorsSettings.kt:105` unimplemented): any site can make credentialed requests. Dev only.
- Preflight from a disallowed origin returns 204 with no CORS headers even when `forbidOnMatchFail=true` (`CorsInterceptor.kt:108-110`, existing TODO), revealing the route exists.
- Trailing TODO #5 in the file says `cacheLength` is `UInt`: stale; it is `Duration?`.

```json
{
  "cors": {
    "limitToDomains": ["https://app.example.com", "*.example.com"],
    "limitToHeaders": ["*"],
    "limitToMethods": ["*"],
    "exposedHeaders": [],
    "allowCredentials": true,
    "cacheLength": "PT10S",
    "forbidOnMatchFail": true
  }
}
```

### RateLimitSettings
Module `ratelimit`, `com.lightningkite.lightningserver.ratelimit.RateLimitSettings`, `ratelimit/src/main/kotlin/com/lightningkite/lightningserver/ratelimit/RateLimitInterceptor.kt:33-38`. `@Serializable` data class. Consumed as `Runtime<RateLimitSettings?>` by `RateLimitInterceptor` (`:86-88`); **`null` disables limiting**. Only in-repo use: `setting<RateLimitSettings?>("clientTelemetryRateLimit", RateLimitSettings(rateLimiterId = "client-telemetry"))` (`typed/src/main/kotlin/com/lightningkite/lightningserver/typed/ClientTelemetryEndpoints.kt:88`). Key is app-chosen.

| field | type | default | meaning |
|---|---|---|---|
| `headerPrefix` | `String` | `"X-RateLimit-"` | Headers are `{prefix}Identity`, `{prefix}RemainingTime`, `{prefix}AvailableAfter` (`:112-116`). Concatenated raw: include the trailing dash. |
| `rateLimiterId` | `String` | `"general"` | Namespaces the cache key `rateLimiter-{id}-{key}:` and appears in the 429 error detail `rate-limit-{id}`. Use distinct ids per limiter. |
| `includeHeaders` | `Boolean` | `true` | Adds the three headers to HTTP responses (not WebSockets). |

Not in the setting: `RequestLimits` (`:60-66`, not `@Serializable`) is returned per request by app code (key, multiplier=10.0, leeway=200s, borrowTime=10s, overhead=0.25s). The JSON cannot tune budgets, only labeling and headers.

Surprises: state lives in the app's `Cache` (in-memory cache = per-process limits). Disabling requires the app to declare the setting nullable (as `clientTelemetryRateLimit` is).

```json
{ "rateLimit": { "headerPrefix": "X-RateLimit-", "rateLimiterId": "general", "includeHeaders": true } }
```
Disable (nullable declarations only): `"clientTelemetryRateLimit": null`.

### OauthProviderCredentials
Module `sessions-oauth`, `com.lightningkite.lightningserver.sessions.proofs.oauth.OauthProviderCredentials`, `sessions-oauth/src/main/kotlin/com/lightningkite/lightningserver/sessions/proofs/oauth/OauthProviderCredentials.kt:13-17`. `@Serializable` data class; no validation; no defaults (both required in JSON; an app's code default must be supplied, e.g. `OauthProviderCredentials("", "")`).

| field | type | default | meaning |
|---|---|---|---|
| `id` | `String` | required | OAuth client ID (Microsoft: Application (client) ID). Sent as `client_id`. |
| `secret` | `String` | required | Client secret, sent as `client_secret` in the token request. |

Used by Google, Microsoft, GitHub (`OauthProviderInfo.google/microsoft/github`, `settings = SettingInfo.standard`, default at `OauthProviderInfo.kt:62`) and any custom `OauthProviderInfo`. Consumed as `Runtime<OauthProviderCredentials>` by `OauthProofEndpoints` (`OauthProofEndpoints.kt:54`) and `OauthCallbackEndpoint`; the app declares the setting (key app-chosen). No provider-specific builders; URLs and scopes are fixed in the `OauthProviderInfo` companion (google `.../auth/userinfo.email`; microsoft `openid email profile`; github `user:email read:user`, query response mode; apple `email`). KDoc says the redirect URI is "auth URL + `/oauth/<provider>/callback`"; in code the callback is `path.path("callback")` under wherever `OauthProofEndpoints` is mounted, so the real URI depends on the mount point (demo mounts at `proof/github`).

```json
{ "googleOAuth": { "id": "1234-abc.apps.googleusercontent.com", "secret": "GOCSPX-..." } }
```

### OauthProviderCredentialsApple
Same module/package, `.../oauth/OauthProviderCredentialsApple.kt:27-33`. `@Serializable` data class; all four fields required; no validation at construction (a bad key fails at first token generation).

| field | type | default | meaning |
|---|---|---|---|
| `serviceId` | `String` | required | Apple Service Identifier; becomes client `id` and JWT `sub`. |
| `teamId` | `String` | required | JWT `iss`. |
| `keyId` | `String` | required | JWT header `kid`. |
| `keyString` | `String` | required | `.p8` contents. Bare base64 body, or a full PEM (if it contains `-----BEGIN` it is used as-is, `:61-65`). Multi-line PEM needs `\n` escapes in JSON. P-256 only (`:69`). |

Behavior: `toOauthProviderCredentials()` returns `OauthProviderCredentials(id = serviceId, secret = generateJwt())`. The secret is an ES256 JWT with `aud` `https://appleid.apple.com`, `iat` = now minus 1 day, `exp` = iat plus 5 days (valid about 4 more days). Generated on every call; no caching seen (`:34-56`).

Wiring: `OauthProviderInfo.apple` sets `settings = SettingInfo.apple` (`OauthProviderInfo.kt:74-75,201`), but nothing in main sources reads `OauthProviderInfo.settings` (only the declaration), so I found no automatic conversion. `OauthProofEndpoints` takes `Runtime<OauthProviderCredentials>`, so the app must convert, e.g. `setting("appleOAuth", OauthProviderCredentialsApple(...))` then `credentials = { appleOAuth().toOauthProviderCredentials() }`. `docs-guide/drafts/oauth.md:106` claims the conversion is automatic: unverified, likely stale. Apple profile comes from `id_token`, verified by `AppleJwtVerifier` with the credentials' `id` as expected audience.

```json
{
  "appleOAuth": {
    "serviceId": "com.example.web",
    "teamId": "ABCDE12345",
    "keyId": "XYZ987WVU6",
    "keyString": "MIGTAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBHkwdwIBAQQg..."
  }
}
```

## Known issues found while writing this

These came from reading the code.  None were reproduced.

- **Random `secretBasis` default:** the default is generated fresh in each process.  It only bites when defaults are used without a settings file (`readyUsingDefaults()`, tests).  Each instance or restart then signs with a different key, so sessions and proofs fail.
- **`SecretBasis` length checks:** input shorter than 64 bytes fails with a raw slice exception.  Longer input is silently truncated.  The `require(size == 64)` can never fail, and `BASE64_CHARS = 66` is wrong: 64 bytes is 88 base64 characters.  `docs-guide/guide/aws-deployment.md:252` says 32 characters, which is stale.
- **`SecretBasis` in logs:** `SecretBasis` is a data class, so its `toString()` would include the secret if it is ever logged.
- **AWS settings loading:** in `AwsAdapter.loadSettings` (`AwsAdapter.kt:100-118`), the Secrets Manager branch isn't exclusive.  It falls through to the file/S3 branches, which looks like a missing `else`.
- **Typo'd keys and fields:** `ignoreUnknownKeys` silently ignores them, and the default is used instead.
- **Stale `wsUrl`:** `wsUrl` is only derived when it's omitted.  Suggested files write it out explicitly, so changing `publicUrl` alone leaves `wsUrl` pointing at the old host.
- **`general.debug` KDoc:** it claims `debug` affects CORS, but the code only uses it to add stack traces to 500 responses.  The `telemetry` KDoc is also stale: it describes a null default and `OpenTelemetrySettings`.
- **Netty WebSockets:** Netty ignores `webSocketSettings.ping` and `pongTimeout`, and appears to ignore `forceWebSocketPubSub`.
- **CORS preflight:** a preflight from a disallowed origin returns 204 even with `forbidOnMatchFail=true` (`CorsInterceptor.kt:108-110`, which has a TODO).  `*example.com` (no dot) matches `evilexample.com`.  `allowCredentials` with `["*"]` isn't rejected.
- **Apple OAuth:** `OauthProviderInfo.settings` / `SettingInfo.apple` is never read.  The app must call `toOauthProviderCredentials()` itself, though `docs-guide/drafts/oauth.md:106` says the conversion is automatic.
