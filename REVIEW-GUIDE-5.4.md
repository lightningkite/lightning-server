# Review guide: `hn/auditing-and-engines` vs `version-5.4`

Range: `origin/version-5.4...HEAD`, first written at `9f39ebba1` (65 commits, 402 files, +22.6k / −9k).
Updated after the suggested fixes (`4529cddac`) and the interceptor rework that split interceptors into physical
(HTTP, WebSocket) and logical (execution) chains. Line links in untouched sections may have drifted.
- About a third of the diff is tests and docs.
- `version-5.4` is a direct ancestor of this branch, so nothing in 5.4 is missing here.
- Line numbers are at HEAD.

Short path prefixes used below:
- `core/…` = `core/src/main/kotlin/com/lightningkite/lightningserver/`
- `audit/…` = `audit/src/main/kotlin/com/lightningkite/lightningserver/audit/`
- `sessions/…` = `sessions/src/main/kotlin/com/lightningkite/lightningserver/sessions/`
- `typed/…` = `typed/src/main/kotlin/com/lightningkite/lightningserver/typed/`

Before reading the code, read these two plans for the intent:
- [plans/execution-context-refactor.md](plans/execution-context-refactor.md)
- [plans/audit-logging.md](plans/audit-logging.md)

---

## 0. Read first: likely bugs and must-decide items

The subagents that read each area found these. I checked the first one myself.

| # | Issue | Where | Status |
|---|---|---|---|
| 1 | **`BackupCodeSecret.createdAt` is required with no default.** Existing rows won't deserialize. Needs a default or a migration. | [BackupCodeEndpoints.kt:50](sessions/src/main/kotlin/com/lightningkite/lightningserver/sessions/proofs/BackupCodeEndpoints.kt#L50) | **Fixed.** Defaults to the epoch, so old rows decode. Nothing reads `createdAt` yet. |
| 2 | **Multiplex physical disconnect.** Each virtual `close` *sends* over the dead socket. On AWS that is a `GoneException`, then `clean()`, then `WebSocketStateGoneException`. The exception escapes `forEach`, so the remaining channels never run `disconnect`. | [MultiplexWebSocketHandler.kt:341-356](core/src/main/kotlin/com/lightningkite/lightningserver/websockets/MultiplexWebSocketHandler.kt#L341) | **Fixed.** Virtual `close` is a no-op during physical teardown, and each channel disconnects in its own `try`. |
| 3 | **`QueryParamWebSocketHandler` execution ids.** The inner Connect execution reuses `socketId`, so it duplicates its parent's execution id. That is a risk for `OriginRecord`. The physical socket is also closed twice, and the inner handler is not intercepted. | [QueryParamWebSocketHandler.kt:146](core/src/main/kotlin/com/lightningkite/lightningserver/websockets/QueryParamWebSocketHandler.kt#L146), [WebSocketExecution.kt:226-233](core/src/main/kotlin/com/lightningkite/lightningserver/runtime/WebSocketExecution.kt#L226) | **Fixed.** The inner socket is a `subConnection` with a fresh `socketId`, and closes once. It goes through the execution chain only, since the WebSocket chain already wrapped the shim. |
| 4 | **`RawHttpEndpoint` / `RawWebSocketPath.tryResolve` race.** It memoizes in plain vars and sets `searched = true` before matching. A concurrent reader can get `null`, which surfaces as a spurious RouteNotFound. | [RawHttpEndpoint.kt](core/src/main/kotlin/com/lightningkite/lightningserver/pathing/RawHttpEndpoint.kt), [RawWebsocketPath.kt](core/src/main/kotlin/com/lightningkite/lightningserver/pathing/RawWebsocketPath.kt) | **Fixed.** `searched` is written last and is `@Volatile`. |
| 5 | **Close reason leaks onto the wire.** Engines now send `exception.message` as the WebSocket close reason. That can leak internal details, and nothing truncates it to the 123-byte RFC limit (Netty may reject a longer one). | [ktor/extensions.kt:113](engine-ktor/src/main/kotlin/com/lightningkite/lightningserver/engine/ktor/extensions.kt#L113), [NettyEngine.kt:314](engine-netty/src/main/kotlin/com/lightningkite/lightningserver/engine/netty/NettyEngine.kt#L314) | **Fixed.** Only an `HttpStatusException` message reaches the client, and engines send `WebSocketClose.truncatedMessage` (123 bytes). |
| 6 | **Ktor header log bug.** It logs `'$it'`, which is the null value, not the header name. | [ktor/extensions.kt:54-58](engine-ktor/src/main/kotlin/com/lightningkite/lightningserver/engine/ktor/extensions.kt#L54) | **Fixed.** |
| 7 | **No version check on proxy request ids.** A trusted proxy request-id is wrapped with `UuidV7.fromRaw` without checking the version. A v4 id gives a garbage `timestamp()`. | [RequestIdentity.kt:52-58](core/src/main/kotlin/com/lightningkite/lightningserver/http/RequestIdentity.kt#L52) | **Fixed**, by `UuidV7.fromRaw` now checking the version. Behaviour change: a proxy stamping v4 ids (Envoy's default `X-Request-ID`) is no longer adopted, and every request logs a warning. |
| 8 | **`ExecutionInterceptor` errors escape.** An exception thrown there escapes `handleRoot` with no HTTP response, because the try/catch sits inside the chain. | [HttpExecution.kt:123-180](core/src/main/kotlin/com/lightningkite/lightningserver/runtime/HttpExecution.kt#L123) | **Fixed.** A failing execution interceptor gives a logged 500. |
| 9 | **Audit fails closed.** If the audit DB is down, every audited read or response fails. The mutation log writes *after* the change, so a retry can apply it twice. Decide whether that's acceptable. | §1.5 | Open: decide. |
| 10 | **Origin recording resolves auth on every request.** This includes public endpoints, which could also fire `SessionRejected` events there. | [OriginRecording.kt:238-244](audit/src/main/kotlin/com/lightningkite/lightningserver/audit/OriginRecording.kt#L238) | Open. |
| 11 | **Stray text.** These comments have a stray `// Claude:` prefix: [AuthEvent.kt:208](sessions/src/main/kotlin/com/lightningkite/lightningserver/sessions/AuthEvent.kt#L208), [Authentication.kt:247](auth/src/main/kotlin/com/lightningkite/lightningserver/auth/Authentication.kt#L247), [JsonRpcHandler.kt:100](typed/src/main/kotlin/com/lightningkite/lightningserver/typed/jsonrpc/JsonRpcHandler.kt#L100). | | **Intentional.** The project convention (`CLAUDE.md`) prefixes agent-written comments with `Claude: `. |
| 12 | **Stale docs.** <ul><li>The `ExecutionInterceptor` KDoc example uses an old signature ([:21-30](core/src/main/kotlin/com/lightningkite/lightningserver/runtime/ExecutionInterceptor.kt#L21)).</li><li>The `HttpExceptionHandler` KDoc still names `ExceptionHttpHandler`, and its `timeout` is never read ([:23-31](core/src/main/kotlin/com/lightningkite/lightningserver/http/HttpExceptionHandler.kt#L23)).</li><li>`Engine.dispatchTask` docs say `from` is nullable, but it isn't ([Engine.kt:122](core/src/main/kotlin/com/lightningkite/lightningserver/runtime/Engine.kt#L122)).</li><li>The plan's "Known gaps" entry about WebSocket tests is out of date.</li><li>`audit-implementation-risks.md` references a seal/hash chain that doesn't exist.</li></ul> | | Partly fixed: the `ExecutionInterceptor` KDoc example is current. The rest is open. |
| 13 | **`TimeInZone` primary constructor made private.** Unexplained, and it arrived inside the `@Queryable` commit. Confirm it is intended. | [notificationModels.kt:21](notifications-shared/src/commonMain/kotlin/com/lightningkite/lightningserver/notifications/notificationModels.kt#L21) | Open: confirm. |
| 14 | **TOTP status code.** An unknown principal type still throws `IllegalArgumentException` (500). Password was fixed to 400. | [TimeBasedOTPProofEndpoints.kt:163](sessions-totp/src/main/kotlin/com/lightningkite/lightningserver/sessions/proofs/TimeBasedOTPProofEndpoints.kt#L163) | Open. |

---

## 1. Conceptual changes (review these deeply)

### 1.1 `Engine` / `ServerRuntime` / `Execution` split
This is the root of most of the diff.
- **Old:** `ServerRuntime` was the process-wide server.
- **New:**
  - The process-wide server is renamed **`Engine`** ([Engine.kt:35](core/src/main/kotlin/com/lightningkite/lightningserver/runtime/Engine.kt#L35)).
  - **`ServerRuntime`** is now a final data class: an engine plus one `Execution` ([ServerRuntime.kt:36](core/src/main/kotlin/com/lightningkite/lightningserver/runtime/ServerRuntime.kt#L36)). Only the framework creates one ([:61](core/src/main/kotlin/com/lightningkite/lightningserver/runtime/ServerRuntime.kt#L61), `Engine.execute` at [:75](core/src/main/kotlin/com/lightningkite/lightningserver/runtime/ServerRuntime.kt#L75)).
  - `ServerRuntimeBase` is renamed `EngineBase`.
  - **`Execution`** is a new sealed type ([Execution.kt:38](core/src/main/kotlin/com/lightningkite/lightningserver/runtime/Execution.kt#L38)): `Http`, `WebSocket` (one per phase, with a shared `socketId`), `Task` (carries `origin`), `Schedule`, `Startup`, `PreDeploy` and `Direct`.
  - The `ID` is a UUID v7 minted from the engine clock.
  - Each execution has `parent`, `rootExecution` and a **`context`** cache. The cache is copied to child executions and serialized into queued tasks ([:86](core/src/main/kotlin/com/lightningkite/lightningserver/runtime/Execution.kt#L86)).
- **Rule of thumb:** code that does work takes `context(ServerRuntime)`; setup code and shared reads take `context(Engine)`.
- **Review focus:**
  - Child runtimes wrap the *parent runtime*, so `history` grows with nesting.
  - `RuntimeDeferred.Cached` is now per engine (via `processEngine`, [ServerSetting.kt:51,118](core/src/main/kotlin/com/lightningkite/lightningserver/definition/ServerSetting.kt#L51)), where it used to be global.
  - The copied `context` keeps `localOnly` entries.
  - The serialized `Execution` and `context` are now a persisted compatibility surface: task queues and DynamoDB socket rows.

### 1.2 Single execution seam: `handleRoot` / `*AsRoot` / `*WithMetrics`, `@EngineApi`, `@OverrideOnly`
- **Deleted:** `implementationHelpers.kt` and `ServerRuntime.ext.kt`.
- **Replacements:**
  - HTTP: [HttpExecution.kt](core/src/main/kotlin/com/lightningkite/lightningserver/runtime/HttpExecution.kt). The root entry is `handleRoot` ([:57](core/src/main/kotlin/com/lightningkite/lightningserver/runtime/HttpExecution.kt#L57)), sub-requests use `ServerRuntime.handle` ([:69](core/src/main/kotlin/com/lightningkite/lightningserver/runtime/HttpExecution.kt#L69)), and child calls use `handleWithMetrics` ([:22](core/src/main/kotlin/com/lightningkite/lightningserver/runtime/HttpExecution.kt#L22)).
  - Tasks: [TaskExecution.kt](core/src/main/kotlin/com/lightningkite/lightningserver/runtime/TaskExecution.kt). The old overloads collapse into `executeTaskLike` ([:52](core/src/main/kotlin/com/lightningkite/lightningserver/runtime/TaskExecution.kt#L52)), and the `location` parameter is gone.
  - WebSockets: [WebSocketExecution.kt](core/src/main/kotlin/com/lightningkite/lightningserver/runtime/WebSocketExecution.kt). Each phase has an `xAsRoot` (engine) and an `xWithMetrics` (child).
- **Annotations** ([annotations.kt](core/src/main/kotlin/com/lightningkite/lightningserver/annotations.kt)):
  - Root helpers are `@EngineApi`.
  - Raw entry points (`HttpHandler.handle`, `Task.executeInline`, the WebSocket phases, `*.execute`) are `@OverrideOnly`.
  - `InternalLightningServerApi` is raised to ERROR.
- **Review focus:**
  - Every root helper runs on `processEngine`, so it never nests inside the runtime in scope. Check this is true everywhere.
  - Connect uses `request.socketId` as its execution id, so that id must be fresh for every virtual sub-socket (see §0 #3).

### 1.3 Interceptors: physical HTTP/WebSocket chains, logical execution chain
- **Deleted:** `Http/WebSocket` × `Connection/Logical` interceptors.
- **New:**
  - **Physical:** `HttpInterceptor` and `WebSocketInterceptor` run only for requests and sockets the engine received ([HttpExecution.kt `handleRoot`](core/src/main/kotlin/com/lightningkite/lightningserver/runtime/HttpExecution.kt)). They see the raw request and response, but never `/meta/bulk` sub-requests, internal endpoint calls, multiplexed virtual sockets or the `QueryParamWebSocketHandler` inner socket. CORS, Gzip, SecurityHeaders and RateLimit live here, with no `isRoot()` guards.
  - **Logical:** [`ExecutionInterceptor`](core/src/main/kotlin/com/lightningkite/lightningserver/runtime/ExecutionInterceptor.kt) wraps every execution of every kind, sub-requests and virtual sockets included. It receives an [`Invocation`](core/src/main/kotlin/com/lightningkite/lightningserver/runtime/Invocation.kt): the execution paired with its `HttpRequest` or `WebSocketConnectRequest` when it has one, held in memory only so serialized executions stay small. `AccessLogInterceptor` and `OriginRecording` live here.
  - `Engine.execute` is still the single place the execution chain runs ([ServerRuntime.kt](core/src/main/kotlin/com/lightningkite/lightningserver/runtime/ServerRuntime.kt)). It sits inside the telemetry span and outside the physical chains.
- **Review focus:**
  - An exception thrown by an execution interceptor skips the physical chains: the client gets a logged 500 with no CORS or security headers. A rejection a browser must read belongs in an `HttpInterceptor`.
  - Rate limiting is per physical request: a bulk call is charged once for its wall-clock duration.
  - Execution interceptors can't see the response or close reason. `AccessLogInterceptor` logs `ok` (or a thrown `HttpStatusException`'s status) for internal calls, and its WebSocket close line no longer carries the reason.
  - `HttpInterceptor.intercept` is generic over `PATH` and is no longer a SAM interface, so lambda interceptors break.

### 1.4 WebSockets: connection is no longer a runtime, and sockets have a `socketId`
- **Old:** `WebSocketConnection` was a `ServerRuntime`.
- **New:**
  - Phases are `context(ServerRuntime)` with the connection passed as an argument ([WebSocket.kt:190-250](core/src/main/kotlin/com/lightningkite/lightningserver/websockets/WebSocket.kt#L190), [WebSocketHandler.kt:21-46](core/src/main/kotlin/com/lightningkite/lightningserver/websockets/WebSocketHandler.kt#L21)).
  - `send`, `subscribe` and the state calls each need a runtime.
  - `WebSocketConnectRequest` replaces the `requestId`/`parentRequestId` strings with a required `socketId: Execution.ID` ([WebSocket.kt:117-167](core/src/main/kotlin/com/lightningkite/lightningserver/websockets/WebSocket.kt#L117)).
  - The `*NoOp` helpers are removed.
  - New: [DelegatingWebSocketHandler](core/src/main/kotlin/com/lightningkite/lightningserver/websockets/DelegatingWebSocketHandler.kt), [WebSocketSubscriptions.kt](core/src/main/kotlin/com/lightningkite/lightningserver/websockets/WebSocketSubscriptions.kt).
  - Typed equivalent: `ApiWebSocketHandler.Connection` no longer extends `ServerRuntime` ([ApiWebsocketHandler.kt:36](typed/src/main/kotlin/com/lightningkite/lightningserver/typed/ApiWebsocketHandler.kt#L36)).
- **Review focus:** this breaks every custom socket handler.

### 1.5 New `audit` / `audit-shared` modules
Opt-in. Nothing outside the modules depends on them, the demo doesn't use them, and there's no user doc in `docs/` yet. `:audit:test` (185) and `:audit-shared:jvmTest` (34) pass.

| Piece | What it does |
|---|---|
| [AuditingBuilder](audit/src/main/kotlin/com/lightningkite/lightningserver/audit/AuditingBuilder.kt) | A `ServerBuilder` you `include`. It wires everything below, with lazy `Audit_*` tables. |
| [OriginRecording](audit/src/main/kotlin/com/lightningkite/lightningserver/audit/OriginRecording.kt) | An `ExecutionInterceptor`, installed with `install(origins)`. It reads the request from the `Invocation`, builds an `OriginRecord` per originating execution, keeps it in the execution context, and only writes it the first time an audit record references it. |
| [AuditRegistry](audit/src/main/kotlin/com/lightningkite/lightningserver/audit/AuditRegistry.kt) | Assigns a permanent `ModelTypeId` per model and a permanent bit per `@Audited` field (max 64, never reused). The assignment runs as a pre-deploy task. |
| [AuditDisclosureLog](audit/src/main/kotlin/com/lightningkite/lightningserver/audit/AuditDisclosureLog.kt) | An `EncodingInterceptor` (§2.9). Every audited record that is encoded produces a disclosure row holding a field bitmask. |
| [AuditMutationLog](audit/src/main/kotlin/com/lightningkite/lightningserver/audit/AuditMutationLog.kt) | Table wrapper. Writes the changed audited fields as `Partial` JSON. |
| [AuditQueryLog](audit/src/main/kotlin/com/lightningkite/lightningserver/audit/AuditQueryLog.kt) | Table wrapper. Writes one row per query: condition, sort, modification. |
| [AuthEventLog](audit/src/main/kotlin/com/lightningkite/lightningserver/audit/AuthEventLog.kt) | An `AuthEventListener` (§1.6). |
| [AuditedModel.kt](audit-shared/src/commonMain/kotlin/com/lightningkite/lightningserver/audit/AuditedModel.kt) | Opt-in point: implement `AuditedModel<ID>` and mark fields with `@Audited`. |

**Review focus, most important first:**
1. **Fail-closed behavior.** A failed disclosure insert fails the response ([AuditDisclosureLog.kt:79-91](audit/src/main/kotlin/com/lightningkite/lightningserver/audit/AuditDisclosureLog.kt#L79)). A failed query insert blocks the query, including at startup ([AuditQueryLog.kt:54-76](audit/src/main/kotlin/com/lightningkite/lightningserver/audit/AuditQueryLog.kt#L54)). The mutation log writes after the change ([AuditMutationLog.kt:94-97](audit/src/main/kotlin/com/lightningkite/lightningserver/audit/AuditMutationLog.kt#L94)). The auth event log fails open. Risks §1 and §7 acknowledge this. Tested in `AuditFailureModeTest`.
2. **Audit tables store sensitive data.** Mutation `data` and query `condition` hold the audited values in plain JSON (e.g. `ssn eq …`). Access control, retention and erasure have no design yet.
3. **Attribution.**
   - Bulk sub-requests take their parent from the context.
   - Tasks inherit the context through serialization.
   - WebSocket phases park the origin in `request.cache` under a per-socket key ([OriginRecording.kt:178-202](audit/src/main/kotlin/com/lightningkite/lightningserver/audit/OriginRecording.kt#L178)). Does that survive between phases on AWS? Only the local engine is tested.
   - The table wrappers capture the `ServerRuntime` at wrap time. A wrapped table kept beyond one execution would misattribute.
4. **The persisted format is forever.**
   - Serial names are inconsistent: disclosure uses `o/rt/rid/d`, mutation and query use `ogn/op/mt/mid`, and origin and auth use full names.
   - The 64-bit `FieldIdentifierSet` capacity is baked into stored rows.
   - `OriginRecord<REQUEST_INFO>` is generic.
   - The query log stores `DataClassPath.toString()`, which is not a stable format.
   - Freeze or fix all of this before release.
5. **Registry.**
   - Only models reachable from typed-endpoint signatures get registered. Anything else throws at runtime, which becomes a 500 because the logs fail closed ([AuditRegistry.kt:79-113](audit/src/main/kotlin/com/lightningkite/lightningserver/audit/AuditRegistry.kt#L79)).
   - No unique constraint on the id or bit, so concurrent pre-deploys can collide.
   - The assignments are cached for the life of the process.
6. **Cost.**
   - `anythingAudited()` walks the descriptor graph on every encode, including every WebSocket frame, with no caching ([isAudited.kt:22-32](audit/src/main/kotlin/com/lightningkite/lightningserver/audit/isAudited.kt#L22)).
   - Origin capture resolves auth on every request (§0 #10).
7. **Query log coverage depends on `Table by wraps`.** A data method added to `Table` later would silently skip logging.

### 1.6 Auth event system
- **Old:** auth failures were only `println`'d in debug mode.
- **New:**
  - A sealed `AuthEvent` with 8 subtypes (token, session and proof rejected or accepted, issued, session created or terminated).
  - Listeners are added with `ServerBuilder.install(listener)` and run through `reportAuthEvent`, which logs and swallows listener errors ([AuthEvent.kt:25-216](sessions/src/main/kotlin/com/lightningkite/lightningserver/sessions/AuthEvent.kt#L25)).
  - Every proof method and the session path now report events ([SessionManager.kt:263-273, 304-339](sessions/src/main/kotlin/com/lightningkite/lightningserver/sessions/SessionManager.kt#L263)).
- **Review focus:**
  - `principal` holds the email or phone number for PIN methods, so events contain PII.
  - OAuth `ProofAccepted` has no request.
  - `install` is a very generic name to put on `ServerBuilder`.
  - The `CancellationException` handling at [:211](sessions/src/main/kotlin/com/lightningkite/lightningserver/sessions/AuthEvent.kt#L211).

### 1.7 Internal typed calls and `.test()` run as child executions
- **Old:** `endpoint(input)` called `handle()` directly, with no interceptors, validation or metrics.
- **New:**
  - It runs as a child `Execution.Http` through the execution interceptors, with input validation and the endpoint's own auth requirement ([ApiHttpHandler.ext.kt `handleWithMetrics`](typed/src/main/kotlin/com/lightningkite/lightningserver/typed/ApiHttpHandler.ext.kt), [HttpExecution.kt `executeWithMetrics`](core/src/main/kotlin/com/lightningkite/lightningserver/runtime/HttpExecution.kt)).
  - It does not run the HTTP interceptors, which are physical (§1.3).
  - The output is returned as-is and the endpoint's exceptions are thrown as-is. Nothing is encoded, so encoding observers (and disclosure auditing) don't fire on internal calls.
  - A handler timeout is thrown as a 503 `HttpStatusException`.
- **Tests:** typed `.test()` ([testing.kt](typed/src/main/kotlin/com/lightningkite/lightningserver/typed/testing.kt)) runs the same way, and checks the `AuthRequirement` by seeding `Authentication.CacheKey`. Downstream tests that passed a non-qualifying auth will now fail, which is correct. Tests that relied on HTTP interceptors running under `.test()` must use `handleRoot`.
- **Review focus:**
  - Validation now applies to internal callers.
  - Execution interceptors see internal calls, so access logs and origin records gain a row per internal call.

---

## 2. Behavior and API changes (contained, but real)

### Data and persistence
1. **Backup codes are marked used instead of deleted** ([BackupCodeEndpoints.kt:166-170, 340-372](sessions/src/main/kotlin/com/lightningkite/lightningserver/sessions/proofs/BackupCodeEndpoints.kt#L340)).
   - A code is claimed with an `updateOne` conditional on `usedAt == null`, which closes a double-redeem race.
   - **Schema break: see §0 #1.** Also confirm the conditional update is atomic on every database backend.
2. **AWS: DynamoDB rows from before the deploy are detected** ([AwsWebSocketDynamoDb.kt:32-49, 252-281](engine-aws-serverless/src/main/kotlin/com/lightningkite/lightningserver/engine/awsserverless/AwsWebSocketDynamoDb.kt#L32)).
   - A legacy row ends the socket with `GOING_AWAY` and cleans it up ([AwsAdapterWs.kt:268-277](engine-aws-serverless/src/main/kotlin/com/lightningkite/lightningserver/engine/awsserverless/AwsAdapterWs.kt#L268)). Every socket open during the deploy drops once.
   - Subscription keys moved from external to internal `topicPath()` format, so old subscription rows may never match again.
3. **`ModelInfo.baseTable()` now goes through the `log` decorator.** New `dangerouslyDirectTable()` sits behind `@RequiresOptIn(ERROR) UnauditedDatabaseAccess` ([ModelInfo.kt:49, 90, 210](typed/src/main/kotlin/com/lightningkite/lightningserver/typed/ModelInfo.kt#L90)). Check for double-decoration.
4. **ModelRest partial `query` always includes `_id`** ([ModelRestEndpoints.kt:104](typed/src/main/kotlin/com/lightningkite/lightningserver/typed/ModelRestEndpoints.kt#L104)). Needed for disclosure auditing. Changes the response shape.
5. **Session rows can no longer be deleted over REST** (`Condition.Never`). Termination is the only path ([SessionManager.kt:152](sessions/src/main/kotlin/com/lightningkite/lightningserver/sessions/SessionManager.kt#L152)).
   - Sessions record the real IP and user agent, with no `"test"`/`""` placeholders.
   - New `sessionSignals` constructor parameter, used only by tests.
6. **JWT `cache` claim** now uses `SerializableCache.Serializer(...)` ([JwtTokenFormat.kt:44](sessions/src/main/kotlin/com/lightningkite/lightningserver/sessions/token/JwtTokenFormat.kt#L44)). Confirm tokens already issued still decode.

### Auth and security
7. **Auth cache is scoped by type, and anonymous results are no longer cached** ([Authentication.kt:135-166, 240-319](auth/src/main/kotlin/com/lightningkite/lightningserver/auth/Authentication.kt#L135)).
   - This fixes bulk sub-requests that carry their own token being treated as anonymous.
   - Cost: anonymous requests re-run every auth reader on each lookup.
   - There's no auth-level bulk regression test.
8. **Proof rate limiting** throws `TooManyAttemptsException`, a `BadRequestException` subclass, so the wire response is the same ([constrainProofAttemptRate.kt](sessions/src/main/kotlin/com/lightningkite/lightningserver/sessions/proofs/constrainProofAttemptRate.kt)).
   - Check every proof endpoint kept its limiter.
   - WebAuthN `prove` has no limiter, which predates this branch.
9. **PIN proofs report failures.** A new public `PinHandler.pendingTarget()` adds one extra cache read per attempt ([PinBasedProofEndpoints.kt:72-86](sessions/src/main/kotlin/com/lightningkite/lightningserver/sessions/proofs/PinBasedProofEndpoints.kt#L72)).
10. **Magic-link `send` signatures changed.** `send` now takes `EmailAddressWithName` / `PhoneNumber` plus a `Request`. The email check also compares the display name, so it is stricter ([EmailProofEndpoints.kt:63](sessions-email/src/main/kotlin/com/lightningkite/lightningserver/sessions/proofs/EmailProofEndpoints.kt#L63), [SmsProofEndpoints.kt:78](sessions-sms/src/main/kotlin/com/lightningkite/lightningserver/sessions/proofs/SmsProofEndpoints.kt#L78)).

### Core
11. **`SerializableCache` rewrite** ([SerializableCache.kt:49](core/src/main/kotlin/com/lightningkite/lightningserver/data/SerializableCache.kt#L49)).
    - Adds a `SCOPE` type parameter.
    - Thread-safe: `ConcurrentHashMap` plus per-key mutexes, `getOrPut`, `cachesNull`, `copy()`.
    - Check the concurrent-retrieve path ([:135-159](core/src/main/kotlin/com/lightningkite/lightningserver/data/SerializableCache.kt#L135)). `copy()` drops `updated`.
12. **Request identity moved off `Request`.**
    - `requestId`/`parentRequestId`/`generateRequestId()` are gone; `engineRequestId` is added ([Request.kt:55](core/src/main/kotlin/com/lightningkite/lightningserver/data/Request.kt#L55)).
    - `RequestIdentity.requestId` is now an `Execution.ID` (see §0 #7).
13. **Task launching.** `Task.invoke` is replaced by `Engine.dispatchTask(task, input, from)`. It can only be launched from an execution ([contextShortcuts.kt:83](core/src/main/kotlin/com/lightningkite/lightningserver/runtime/contextShortcuts.kt#L83)), and engines must carry `from` into the queue ([LocalEngine.kt:187](engine-local/src/main/kotlin/com/lightningkite/lightningserver/engine/local/LocalEngine.kt#L187)).
14. **`EncodingInterceptor`** ([EncodingInterceptor.kt:22](core/src/main/kotlin/com/lightningkite/lightningserver/serialization/EncodingInterceptor.kt#L22)) replaces `TypedOutputInterceptor`.
    - Encoders are wrapped once, at finalize, and only when an interceptor is installed.
    - Anything that encodes outside the server's encoders must call `notifyEncoding` itself, as JSON-RPC does.
15. **`HttpExceptionHandler`** ([HttpExceptionHandler.kt:37](core/src/main/kotlin/com/lightningkite/lightningserver/http/HttpExceptionHandler.kt#L37)) replaces `ExceptionHttpHandler`/`DefaultExceptionHttpHandler`.
    - `.Default` is public.
    - `toLSError()` moved to the root package.
16. **Media coder priority reversed** from `sortBy` to `sortByDescending` ([media.kt:32](core/src/main/kotlin/com/lightningkite/lightningserver/serialization/media.kt#L32)).
    - This is a fix: the KDoc says higher wins.
    - It still changes which coder gets picked.
    - `invoke` is renamed `encode`/`decode`.
17. **`TestRunner` is just an `Engine`** ([TestRunner.kt:64](core/src/main/kotlin/com/lightningkite/lightningserver/runtime/test/TestRunner.kt#L64)).
    - Runtime work goes in `execute {}`.
    - WebSocket tests now go through the `AsRoot` helpers, so interceptors fire.

### WebSockets and engines
18. **`MultiplexWebSocketHandler` fixes** ([MultiplexWebSocketHandler.kt](core/src/main/kotlin/com/lightningkite/lightningserver/websockets/MultiplexWebSocketHandler.kt)).
    - A virtual close no longer closes the physical socket.
    - Cancellation is no longer swallowed.
    - Binary frames are decoded instead of crashing on a cast.
    - A missing path is a 400.
    - A physical disconnect reaches every channel without sending over the dead socket (§0 #2).
    - A frame that doesn't decode closes the socket with `VIOLATED_POLICY` and a fixed "Invalid multiplex message", instead of `INTERNAL_ERROR`.
    - Virtual sockets go through the execution chain only (§1.3).
19. **`WebSocketClose` is a data class** (`code`, `message`, `cause`) with `exceptional(e)` ([WebSocketClose.kt:8-72](core/src/main/kotlin/com/lightningkite/lightningserver/websockets/WebSocketClose.kt#L8)).
    - Cancellation maps to `GOING_AWAY`.
    - A timeout maps to `INTERNAL_ERROR`.
    - Only an `HttpStatusException`'s message becomes the reason; the exception stays on `cause`.
    - Engines send `truncatedMessage`: the message, or the code's name, cut to RFC 6455's 123 bytes. Netty used to send an empty reason when there was no message.
    - Telemetry no longer records `ws.disconnect.reason`; `ws.disconnect.code` is the only close attribute, to keep cardinality bounded.
20. **Raw paths have private constructors and `@Unsafe` factories.** An endpoint built from a `ResolvedPath` is pinned to its handler.
    - This fixes a mismatch: the old code built paths in the internal string format but matched against the external one ([RawHttpEndpoint.kt:14-114](core/src/main/kotlin/com/lightningkite/lightningserver/pathing/RawHttpEndpoint.kt#L14), [RawWebsocketPath.kt:34-143](core/src/main/kotlin/com/lightningkite/lightningserver/pathing/RawWebsocketPath.kt#L34)).
    - See §0 #4.
21. **Engines.**
    - **Netty** ([NettyEngine.kt:301-344, 591](engine-netty/src/main/kotlin/com/lightningkite/lightningserver/engine/netty/NettyEngine.kt#L301)):
      - Disconnect runs at most once.
      - The per-socket scope is now a child of the engine scope (it used to leak).
      - Shutdown is ordered.
    - **Ktor:** a single `finally` path ([KtorEngine.kt:274-324](engine-ktor/src/main/kotlin/com/lightningkite/lightningserver/engine/ktor/KtorEngine.kt#L274)).
    - **JDK:** WebSocket upgrades get a 501 ([JdkEngine.kt:138](engine-jdk-server/src/main/kotlin/com/lightningkite/lightningserver/engine/jdk/JdkEngine.kt#L138)).
    - **LocalEngine** drains disconnects before services shut down ([LocalEngine.kt:349-364](engine-local/src/main/kotlin/com/lightningkite/lightningserver/engine/local/LocalEngine.kt#L349)).

### Typed
22. **Typed path shadowing fix.**
    - The implicit `invoke(input)` used to run the target with the *caller's* path. It now rebuilds the target's `ResolvedPath` and is `@Deprecated` ([ApiHttpHandler.ext.kt:152-186](typed/src/main/kotlin/com/lightningkite/lightningserver/typed/ApiHttpHandler.ext.kt#L152)).
    - Check the safety argument for `fromRawPathArguments`.
23. **JSON-RPC** validates params and calls `notifyEncoding` ([JsonRpcHandler.kt:86-101](typed/src/main/kotlin/com/lightningkite/lightningserver/typed/jsonrpc/JsonRpcHandler.kt#L86)).

### Build
24. **Dependency bump.** `serviceAbstractions` goes from `1.3.0-4-fa781902` to **`1.4.0-prerelease-43-787b8ae8`** ([libs.versions.toml](gradle/libs.versions.toml)).
    - It's a prerelease on a release branch.
    - It provides `@Queryable`.
    - Since `prerelease-13`: `UuidV7.fromRaw` returns `null` for a non-v7 UUID (`fromRawUnchecked` doesn't check), `condition(Boolean)` takes no type argument, and `TelemetryBackend` gained `flush()`.

---

## 3. Fallout (skim)

- **`@Queryable`** replaces `@Serializable @GenerateDataClassPaths` on all models (last commit, `9f39ebba1`). Mechanical; it relies on `@Queryable` implying `@Serializable`.
- **`context(ServerRuntime)` → `context(Engine)`** for setup-only APIs: settings, `Runtime`, `ResolvedPath`, `Authentication(...)`, `testAuth`, SDK generation, `DatabaseTableRegistration`, `UploadEarlyEndpoint`. `SDK.Runtime` now extends `EngineBase`.
- **Interceptor ports:** `AccessLogInterceptor` and `OriginRecording` are `ExecutionInterceptor`s (id suffix now from `execution.logicalId`). `RateLimitInterceptor`, CORS, Gzip and SecurityHeaders are physical `HttpInterceptor`/`WebSocketInterceptor`s.
- **`ApiWebSocketHandler` builder lambdas** became `suspend context(ServerRuntime) Connection.() -> …`. `ConnectionWrapper` is now local. `ModelRestUpdatesWebsocket` closes with reason text.
- **Small moves:** `ApiHttpHandler` factories moved verbatim to [ApiHttpHandler.builder.kt](typed/src/main/kotlin/com/lightningkite/lightningserver/typed/ApiHttpHandler.builder.kt). `CacheMutex` lost its stored server. `MetaEndpoints` bulk uses `serverRuntime.handle`.
- **New utilities:** `concurrent/Flow.ext.kt`, `Iterable.ext.kt`, `MutableExtensions.SetOnceKey`/`copy()`, `HttpRequest.subRequest(headers)`.
- **AWS:** `blocking.kt` deleted (CRaC hooks use `runBlocking { launch }`). The adapter uses `handleRoot`/`*AsRoot`.
- **`sessions-shared`, WebAuthN and OAuth files:** large line counts but KDoc condensing only. No wire or SDK changes.
- **Tests:** almost every module's tests were rewritten for `testBlocking`/`execute {}`.
  - New suites: `ExecutionInterceptorTest`, `InitiatorTest`, `TaskParentageTest`, `RuntimeCachedScopeTest`, `EncodingInterceptorTest`, `AuthEventTest`, `BackupCodeEndpointsTest` (+800 lines), Netty and Ktor socket lifecycle tests, AWS legacy-row tests, and the whole audit suite.
  - Stale names remain: `ImplementationHelpersHandleTest`, `DefaultExceptionHttpHandlerTest`.
- **Docs:**
  - Check these against the code, since they were substantively rewritten: [docs/runtime.md](docs/runtime.md), [core runtime index.md](core/src/main/kotlin/com/lightningkite/lightningserver/runtime/index.md), [docs/notifications.md](docs/notifications.md) (reorganized; troubleshooting removed), [docs/deploy-aws-ec2.md](docs/deploy-aws-ec2.md) (rewritten; OTel vendor sections cut), [docs/endpoints.md](docs/endpoints.md) (exception handler), [docs/sessions.md](docs/sessions.md) (auth events), [docs/tasks.md](docs/tasks.md) (startup and pre-deploy), [docs/modules.md](docs/modules.md), [CLAUDE.md](CLAUDE.md), [.claude/skills/lightning-server.md](.claude/skills/lightning-server.md).
  - The rest of `docs/**` and `docs-guide/**` is API renames only.

---

## Breaking changes for downstream apps (migration checklist)

- `ServerRuntimeBase` → `EngineBase`. Engines implement `dispatchTask`; `Task.invoke` is now a context shortcut.
- Code that held a `ServerRuntime` for setup should now take `Engine`. `TestRunner` is not a runtime, so wrap runtime work in `execute {}`.
- `Http/WebSocket{Connection,Logical}Interceptor` → `HttpInterceptor`/`WebSocketInterceptor` for connection concerns, and `ExecutionInterceptor` (`intercept(invocation, cont)`) for anything that must also see sub-requests, internal calls or virtual sockets. Lambda `HttpInterceptor`s no longer compile.
- `requestIdHeader` only adopts version 7 UUIDs.
- Dashboards on `ws.disconnect.reason` must use `ws.disconnect.code`.
- `ExceptionHttpHandler` → `HttpExceptionHandler`. `http.toLSError` → root package.
- `Request.requestId`, `parentRequestId` and `generateRequestId()` are removed. `WebSocketConnectRequest` needs a `socketId`.
- WebSocket handlers: phases take `context(ServerRuntime)` plus the connection, and the `*NoOp` helpers are gone.
- Calling `@OverrideOnly` methods directly or `@EngineApi` without opt-in is now a compile error, and so is using `InternalLightningServerApi`.
- `SerializableCache` keys gain `SCOPE`. Media coder `invoke` → `encode`/`decode`.
- `EmailProofEndpoints.send` / `SmsProofEndpoints.send` signatures changed.
- `@Serializable @GenerateDataClassPaths` → `@Queryable`, which requires service-abstractions `1.4.0-prerelease`.
- `BackupCodeSecret` schema changed (see §0 #1).
- `TimeInZone` primary constructor is private.
- Typed `.test()` now enforces auth, and internal typed calls are validated and run through the execution interceptors, but not the HTTP ones.
