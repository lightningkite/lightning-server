# Plan: Execution Context Refactor (`Engine` / `ServerRuntime` / `Initiator`)

Status: **approved, in implementation**
Target: Lightning Server 5.x
Prerequisite for: the remaining layers of [`audit-logging.md`](audit-logging.md)

---

## 1. Goal

Make it possible to answer **"who or what initiated this execution?"** anywhere in the server, so the
audit system has something to attribute records to.

Today `ServerRuntime` is a *process-wide* object: one per server, carrying settings, serialization,
telemetry, and task dispatch. Nothing in it knows which request, task, or socket is currently
running. Every log that wants that information reconstructs it from whatever happens to be in scope,
which is why `requestId` is threaded manually through `Request` and why the WebSocket lifecycle has
to smuggle a runtime through `WebSocketConnection`.

The fix splits the two concerns that are currently fused:

| Concept | Scope | Contains |
|---|---|---|
| `Engine` | one per server process | everything `ServerRuntime` has today |
| `ServerRuntime` | one per **execution** | an `Engine` (by delegation) + an `Initiator` |

"Execution" means one run of anything the server can run: an HTTP request, one WebSocket lifecycle
phase, a task, a schedule tick, a startup task, or a pre-deploy task.

Because the new `ServerRuntime` is a subtype of `Engine` and keeps its name, **the ~622 existing
`context(server: ServerRuntime)` declarations do not change**. The work is concentrated in the ~8
places that *implement* it.

---

## 2. Design decisions (settled — do not re-litigate)

### 2.1 Location: reuse the `Raw*` path types, do not invent one

An initiator must name *what* is running. The obvious `location: PathSpec0` is wrong twice: it cannot
hold an HTTP endpoint at `PathSpec1` or greater, and it cannot distinguish `GET /x` from `POST /x`.

`RawHttpEndpoint<PATH>` and `RawWebSocketPath<PATH>` already solve this. Both are `@Serializable`,
both carry concrete `PathSegments`, `RawHttpEndpoint` carries the `HttpMethod`, and both resolve the
*pattern* on demand via `.match.path.pathSpec` given an engine. They are already what `Request.path`
holds, so the initiator stores no new information — it stores the same information at a better scope.

Tasks, schedules, startup and pre-deploy tasks are registered under all-constant `PathSpec0` keys, so
`PathSegments` is their complete location and is already `@Serializable`.

**Cardinality note.** The initiator holds the *concrete* path (`/users/abc123`). `RequestRecord.endpoint`
deliberately stores the *pattern* (`/users/{id}`) to keep that column's cardinality bounded — see
`audit-logging.md`. That stays true; the pattern is now derived from the initiator rather than the
request. Do not "fix" `RequestRecord` to store concrete segments.

### 2.2 The initiator is serializable

Required, and not merely for log convenience: it is how `causedBy` crosses a queue. When a task is
launched from a request, the launching `executionId` is serialized into the queued payload, so the
task knows its parent with no database read. This is the only mechanism that works on serverless.

Consequence to accept deliberately: the initiator **will** be persisted — into task queues and into
the DynamoDB WebSocket state row. Everything in it is bounded by URL length. Keep it that way.

### 2.3 The initiator carries no request data

No headers, no source IP, no principal, no body. Those stay on `Request`, which is already threaded
everywhere it is needed. The initiator answers "what is running and why", not "what did the caller
send".

### 2.4 WebSocket phases are separate executions

On AWS each of the five WebSocket lifecycle methods is a **separate Lambda invocation**. Treating a
socket as one execution is therefore factually wrong. Each phase gets its own `executionId`; the
socket's identity is a separate `socketId` that is constant across all phases of that socket.

### 2.5 Parentage: `causedBy` plus `rootExecutionId`

Three real sources of parentage, no more:

| Source | Crosses a process? |
|---|---|
| `/meta/bulk` sub-requests | no — in the call stack |
| multiplexed WebSocket sub-sockets | no |
| a task launched from a request | **yes** — via the serialized initiator |

`causedBy` must live on `Initiator` rather than only in `RequestRecord`, otherwise launching a task
from a request could not stamp parentage without a database read.

`rootExecutionId` is carried as well (**approved**; it originated as a recommendation rather than a
requirement). With parent pointers alone, "show me everything that happened
because of request X" is a recursive join — and that query is the entire point of the audit system.
With a root it is one indexed lookup, for 16 bytes on a row that already holds two UUIDs. This is
consistent with `audit-logging.md`'s rule that parentage lives in the request log and is not repeated
per disclosure; it just lets the request log answer in one hop.

Both are **framework-set**. User code never constructs an `Initiator`.

### 2.6 Ids are `Uuid`, and AWS ids are never adopted

`generateRequestId()` currently calls `SecureRandom.getInstanceStrong()` **per request** — a provider
lookup every time, and on Linux the default strong algorithm is `NativePRNGBlocking`, i.e. a blocking
`/dev/random` read on the request path. `Uuid.random()` replaces it.

API Gateway ids are **not** UUIDs and not 128 bits (HTTP API request ids and WebSocket connection ids
are both the compact ~11-byte base64 form). We do not attempt to map them. We always mint our own.

The join to the gateway's access log is preserved by a new **`engineRequestId: String?`** column on
`RequestRecord`: trusted, engine-supplied, holding the gateway/proxy's own id. It is deliberately
**distinct from `upstreamRequestId`**, which is documented as an untrusted client claim and must not
be conflated with a trusted gateway id. The join is: gateway log `requestId` → `engineRequestId` →
our `Uuid`.

Nothing is lost for WebSockets: `connectionId` already lives in `engineSocketId`.

### 2.7 One seam for minting, interception, and instrumentation

`core/.../runtime/implementationHelpers.kt` is already the single place every engine funnels through:
`handle()`, the five `*WithMetrics` WebSocket helpers, and four near-identical `executeWithMetrics`
overloads for Task / ScheduledTask / StartupTask / PreDeployTask.

That is the same seam that must mint the `ServerRuntime`, the same seam `ExecutionInterceptor` hooks,
and the same seam telemetry already uses. Minting, intercepting and instrumenting all happen there,
once. The four `executeWithMetrics` copies collapse into one generic helper.

### 2.8 `Engine` vs `ServerRuntime` at declaration sites

Anything that genuinely has no initiator — server boot, settings resolution, `sharedResources`,
serialization setup — takes `Engine`. Everything that runs *on behalf of something* takes
`ServerRuntime`. This makes "who initiated this?" answerable by the type system rather than by
convention.

---

## 3. Target shapes

### 3.1 `Initiator`

```kotlin
package com.lightningkite.lightningserver.runtime

@Serializable
public sealed interface Initiator {
    /** Identifies this one execution. */
    public val executionId: Uuid
    /** The execution that caused this one, or null if it started here. Framework-set. */
    public val causedBy: Uuid?
    /** The execution at the head of this causal chain; equals [executionId] when [causedBy] is null. */
    public val rootExecutionId: Uuid

    @Serializable @SerialName("http")
    public data class Http(
        override val executionId: Uuid,
        override val causedBy: Uuid? = null,
        override val rootExecutionId: Uuid = executionId,
        val endpoint: RawHttpEndpoint<PathSpec>,
    ) : Initiator

    @Serializable @SerialName("ws")
    public data class WebSocket(
        override val executionId: Uuid,
        override val causedBy: Uuid? = null,
        override val rootExecutionId: Uuid = executionId,
        /** Constant for the socket's whole lifetime, across all phases. */
        val socketId: Uuid,
        val path: RawWebSocketPath<PathSpec>,
        val phase: Phase,
    ) : Initiator {
        public enum class Phase { Connect, Connected, ClientMessage, SubscriptionMessage, Disconnect }
    }

    @Serializable @SerialName("task")
    public data class Task(..., val location: PathSegments) : Initiator

    @Serializable @SerialName("schedule")
    public data class Schedule(..., val location: PathSegments) : Initiator

    @Serializable @SerialName("startup")
    public data class Startup(..., val location: PathSegments) : Initiator

    @Serializable @SerialName("predeploy")
    public data class PreDeploy(..., val location: PathSegments) : Initiator

    /**
     * Executions with no server-side origin: `TestRunner`, manual invocation. A deliberate hole in
     * "every execution names what started it" — without it nothing outside the server could build a
     * `ServerRuntime` at all.
     */
    @Serializable @SerialName("direct")
    public data class Direct(...) : Initiator
}
```

`RawHttpEndpoint` and `RawWebSocketPath` are generic `@Serializable` classes, so a type argument must
be named. `PathSpec` itself has a serializer (`DummyPathSpecSerializer`), so `RawHttpEndpoint<PathSpec>`
resolves. This is mechanical, not a design question.

### 3.2 `Engine` and `ServerRuntime`

```kotlin
/** One per server process. Exactly today's `ServerRuntime`, renamed. */
public interface Engine : SettingContext, Namespaced { /* unchanged members */ }

/** Today's `ServerRuntimeBase`, renamed. */
public abstract class EngineBase(override val server: ServerDefinition) : Engine { /* unchanged */ }

/** One per execution. */
public interface ServerRuntime : Engine {
    public val initiator: Initiator
}
```

Plus an implementation that is `Engine by engine`, and overrides `Task.invoke` to stamp the current
`executionId` as the launched task's `causedBy` (and propagate `rootExecutionId`).

### 3.3 `WebSocketHandler`

```kotlin
public interface WebSocketHandler<PATH : PathSpec, STORAGE> {
    public val storageSerializer: KSerializer<STORAGE>
    public context(serverRuntime: ServerRuntime)
    suspend fun willConnect(request: WebSocketConnectRequest<PATH>): STORAGE
    public context(serverRuntime: ServerRuntime)
    suspend fun didConnect(connection: WebSocketConnection<PATH, STORAGE>)
    public context(serverRuntime: ServerRuntime)
    suspend fun messageFromClient(connection: WebSocketConnection<PATH, STORAGE>, frame: WebSocketFrame)
    public context(serverRuntime: ServerRuntime)
    suspend fun messageFromSubscription(connection: WebSocketConnection<PATH, STORAGE>, topic: WebSocketSubscriptionMessage<*, *>)
    public context(serverRuntime: ServerRuntime)
    suspend fun disconnect(connection: WebSocketConnection<PATH, STORAGE>, reason: WebSocketClose)
}
```

`WebSocketConnection` **drops `: ServerRuntime`** and keeps only its own members. This is a knowingly
breaking change for downstream consumers writing custom sockets; accepted, as very few exist and the
project is still internal.

### 3.4 `ExecutionInterceptor`

```kotlin
public interface ExecutionInterceptor {
    public val name: String get() = this::class.simpleName ?: "anonymous"
    public suspend fun <T> intercept(
        runtime: ServerRuntime,
        cont: suspend context(ServerRuntime) () -> T,
    ): T
}
```

Installed on `ServerBuilder`, applied at the seam in 2.7, so it covers HTTP endpoints, WebSocket
phases, tasks, schedules, startup **and** pre-deploy uniformly.

---

## 4. Commits

Each stage is one commit, must compile, and must leave `./gradlew check` no worse than the baseline.

### Stage 1 — Request ids become `Uuid`

- `generateRequestId()` → `Uuid.random()`; drop the `SecureRandom` path entirely.
- `Request.requestId` / `parentRequestId` → `Uuid` / `Uuid?`.
- `RequestRecord._id` / `parentRequestId` → `Uuid` / `Uuid?`; `DisclosureRecord.requestId` → `Uuid`.
- Add `RequestRecord.engineRequestId: String?` (see 2.6), separate from `upstreamRequestId`.
- AWS: mint our own `Uuid`; put `requestContext.requestId` in `engineRequestId`. Do **not** adopt.
  WebSocket keeps `connectionId` in `engineSocketId` as it already does.
- Trusted-proxy header path: parse to `Uuid`, generate on failure.
- Known break, already documented and accepted in `audit-logging.md` §3.1: DynamoDB rows holding a
  serialized `WebSocketConnectRequest` fail to deserialize, dropping in-flight sockets on deploy.

### Stage 2 — Reshape `WebSocketHandler`

- Apply 3.3. Six implementation sites: `LocalWebSocketConnection`, `AwsAdapterWs`, plus the wrapper
  handlers (`MultiplexWebSocketHandler`, `QueryParamWebSocketHandler`, `CoroutineWebSocketHandler`,
  `ApiWebSocketHandler`) and the interceptors.
- Removes the `with(connection as ServerRuntime)` casts in `AccessLogInterceptor` and
  `RequestRecordInterceptor`.
- **Add `abstract class DelegatingWebSocketHandler(wrapped)`** in core. `AccessLogInterceptor` and
  `RequestRecordInterceptor` each hand-write all five methods to pass four of them straight through.
- **Fix `WebSocketInterceptor.compileAndInstrument()`**: its 3-branch `when` drops the `name` override
  in the `else` branch and never filters `None`. The HTTP side is a clean `fold`. Unify them.

### Stage 2b — the typed socket loses the fusion too

Stage 2 left `ApiWebSocketHandler.Connection` extending `ServerRuntime`, on the grounds that breaking
it reaches every *typed* socket in user code rather than only hand-written ones. Confirmed as
acceptable: very few custom sockets exist downstream, typed ones included, and most systems use
`ModelRestUpdatesSocket`, which is updated here in the same commit.

So `ApiWebSocketHandler.Connection` drops `: ServerRuntime`, `ConnectionWrapper` stops delegating,
and the typed lifecycle methods take the runtime as a context alongside the connection — the same
shape Stage 2 gave the raw handler, for the same reason. `ModelRestUpdatesWebsocket` and every other
typed socket in the repo follow.

### Stage 3 — `Initiator`

- Add the type per 3.1.
- Move `requestId` / `parentRequestId` **off** `Request` onto `Initiator`. `Request` keeps
  `upstreamRequestId` only (a wire-level fact about the caller).
- AWS persists the initiator alongside the connect request in the DynamoDB socket row so `socketId`
  survives the round trip.

### Stage 4 — `ServerRuntime` → `Engine`, new `ServerRuntime`

- Rename `ServerRuntime` → `Engine`, `ServerRuntimeBase` → `EngineBase`. **Do not touch use sites.**
- Add the new `ServerRuntime` per 3.2 and mint it at the seam in 2.7.
- Move genuinely engine-scoped declarations to take `Engine` (2.8).
- `Task.invoke` stamps `causedBy` / `rootExecutionId`.

### Stage 5 — `ExecutionInterceptor`

- Add per 3.4, install on `ServerBuilder`, apply at the seam.
- Collapse the four `executeWithMetrics` overloads into one.
- Existing telemetry becomes a built-in interceptor.

---

## 5. Out of scope

Everything downstream in `audit-logging.md` — the disclosure log, data access log and auth event log
build on this, but none of them change here.
