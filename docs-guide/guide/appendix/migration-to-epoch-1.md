# Migrating to epoch 1

Epoch 1 (the `1000.0.0` stack catalog) ships Lightning Server 5.4 on Service Abstractions 1.4.  Most renames keep a
deprecated alias with a `ReplaceWith`, so fix compile errors first, then work through the deprecation warnings.
The full list of changes is in the
[changelog](https://github.com/lightningkite/lightning-server/blob/master/CHANGELOG.md).

## Procedure

1. Bump the stack catalog version.
2. Delete stale generated code (`rm -rf */build/generated/ksp`); KSP caches by model, not by processor version.
3. Compile everything and fix errors: those are the removed APIs below.
4. Clean, compile again, and fix the deprecation warnings.

## Database (Service Abstractions 1.4)

Paths became **optics**.  A generated field is a path by itself, so most `condition<User> { }` lambdas that only
built a path are unnecessary:

```kotlin
// before
table.find(condition<User> { it.age gte 18 })
val city = User.path.address.notNull.city

// after
table.find(User.age gte 18)
val city = User.address.notNull.city
```

| Before | After |
|---|---|
| `condition<T> { }`, `modification<T> { }` | `Condition<T> { }`, `Modification<T> { }` |
| `it.always`, `it.never` | `it.always()`, `it.never()`, or `Condition.Always` / `Condition.Never` |
| Generated top-level fields `User_email`, `User__properties` (removed, no alias) | `User.email`, `User.fields` |
| `DataClassPath`, `DataClassPathPartial`, `DataClassPathSelf` | `Optic`, `AnyOptic`, `IdentityLens` |
| `SerializableProperty` | `Field` (now sealed) |
| `path<T>()`, `User.path.age` | `Lens<T>()`, `User.age` |
| `@Serializable @GenerateDataClassPaths` | `@Queryable` |
| `table.insertMany(items)` | `table.insert(items)` |
| `it.fullTextSearch(s)` (removed) | `table.findByTextIndex(...)` |
| `path.condition { }` | `path.mapCondition { }` |

Optic helpers (`notNull`, `elements`, `Lens`, `Field`, ...) moved to `com.lightningkite.services.database.optics`;
add `import com.lightningkite.services.database.optics.*` where `notNull` stops resolving.  Generated fields are
companion extensions in the model's package, so files in other packages import them by name
(`import com.example.models.email`).

Removed condition and modification types with no replacement: map-key conditions and modifications, string
append (`+=`), and list/set drop-first/last.  `assign` the new value instead.

## Server

- **Tests: `TestRunner` is an `Engine`, not a `ServerRuntime`.**  Handler `.test()` calls are unchanged, but test
  code that does server work directly (table access, `topic.send`, a `context(ServerRuntime)` helper) must run in
  `execute { }`, which returns the block's value:

  ```kotlin
  Server.testBlocking(settings = { database set Database.Settings("ram") }) {
      execute { chatTopic.send("Pong.") }
      val user = execute { users.info.table().get(id) }
  }
  ```

- **Engines aren't a `ServerRuntime` either.**  Work a launcher did directly on the engine, such as running
  migrations inside `KtorEngine(...).apply { }`, no longer compiles.  Bind it as a `PreDeployTask` and run it with
  `runPreDeploy()` (a `predeploy` command) or `runPreDeployTasksBlocking()` before `start()`.  See
  [Tasks](../tasks.md).
- **`modelInfo(...)` requires `tableName`.**  The old default was the model's class name; pass exactly that
  (`tableName = "Task"`) or existing data is orphaned.
- **Custom `SDK.Format`s** take `context(Engine)` in `write`, not `context(ServerRuntime)`.
- **`PathSpec0.include(ServerDefinition)` is removed**, along with the SDK's `module(SdkModule<ServerDefinition>)`
  and `ServerDefinition.withSdkInfo`.  Include the `ServerBuilder` instead.
- **Notification registration checks are build checks**: a missing event registration fails `build()` instead of
  startup.  See [Architecture](../architecture.md#build-checks).
- **`Invocation.Http` has a required `origin`**, and `Invocation.WebSocket` a required `virtual`.  Only custom
  code that constructs invocations is affected.  See [Architecture](../architecture.md#invocation).
- **`RateLimitInterceptor`** is now an `ExecutionInterceptor` and `HttpInterceptor` (no longer a
  `WebSocketInterceptor`).  New `exempt` and `exemptSockets` parameters come before `requestLimits`, so pass that
  by name or as a trailing lambda.  Exempt your `MetaEndpoints.bulk` and `MultiplexWebSocketHandler`, or the
  wrapper is charged as well as its parts.  See [Rate limiting](../rate-limiting.md).

## Auth and sessions

- **TOTP proofs moved** to their own artifact: add `sessions-totp` if you use `TimeBasedOTPProofEndpoints`.
- **`Authentication.issuedAt` is now `sessionIssuedAt`**; `tokenIssuedAt` is new.  Custom `TokenFormat`s must
  implement `expiration`.
- **Access tokens minted before the upgrade are rejected**, since the JWT claims and tiny-token encoding changed.
  Clients get a new access token on their next refresh.
- **Proof screening**: `EmailProofEndpoints(verifyEmail = ...)` becomes `screenEmail: (EmailAddress) ->
  ScreeningResult`, and `SmsProofEndpoints(verifyPhone = ...)` becomes `screenPhone`.  The old parameters still
  work, deprecated.  `WebAuthNProofEndpoints`' `expiration` is now `challengeExpiration`.
- **Sub-sessions** inherit their parent's authentication time, can't exceed its scopes, and end when it is
  terminated.  They are created server-side with `newSubSession`: the `POST sub-session` endpoint, the client's
  `subsession` and `SubSessionRequest` are gone, since a stolen access token could mint a long-lived refresh token
  through them.
- **Only access tokens authenticate requests.**  A refresh token sent as `Authorization` now gets a 401: exchange it
  at `token/simple` first.  If old clients can't be updated yet, pass `acceptRefreshTokenAsBearer = Runtime { true }`
  to the `SessionManager` / `AuthEndpoints`.
- **Tokens in URLs work only on WebSocket connects.**  An HTTP request with an `Authorization` or `jwt` query
  parameter gets a 400; send the header.
- **The `Authorization` cookie is no longer read.**  A request carrying only that cookie is unauthenticated.

## Clients

`Fetcher.withHeaderCalculator` is deprecated in favor of `withMetadata`, which edits a copy of the fetcher's
metadata before every request instead of replacing it:

```kotlin
val authApi = api.withMetadata { set("Authorization", accessToken()) }
```

Custom `Fetcher` implementations must implement `withMetadata`.  Regenerate your SDKs; old generated code still
compiles with deprecation warnings.  See [Client SDKs](../client-sdks.md).
