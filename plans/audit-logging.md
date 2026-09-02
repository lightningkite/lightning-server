# Plan: Audit Logging for High-Sensitivity Environments

Status: **in implementation** — [section 3](#3-layer-0-request-identity) is built; the rest is design.
See [Implementation order](#9-implementation-order) for what is done and what is next.
Target: Lightning Server 5.x
Scope: the code-side layers only. The external capture layer (reverse-proxy mirror → encrypted
write-only bucket) is a deployment concern and is deliberately **out of scope for this document**;
see [Out of scope](#10-out-of-scope-external-capture-layer) for the constraints it imposes on the
code-side work.

---

## 1. Motivation

`AccessLogInterceptor` is a *request* log: it records who hit which path. That answers "who touched
this system," which is not the question an auditor in a high-sensitivity environment asks. They ask:

> Show me every time record X was disclosed, to whom, and how much of it.

That is a **subject-indexed** question, and nothing in the framework can answer it today. Concretely,
if a client calls `POST /query` on a `ModelRestEndpoints`, the current log records only that
`/query` was accessed. It does not record what was queried, what came back, or how much of each
record was visible after masking.

There are also several channels through which data is disclosed with **no logging at all** (see
[section 4](#4-layer-1-fixes-to-the-access-log)).

## 2. Design principle: four logs, four questions

The central design decision is to stop trying to make one layer answer everything. Each layer
answers a distinct question and is hooked at a distinct choke point.

| Layer | Question it answers | Hook point | Status |
|---|---|---|---|
| **Disclosure log** | What data left the building, and to whom? | `ApiHttpHandler.handle` (typed layer) | new — [section 5](#5-layer-2-the-disclosure-log-audited) |
| **Data access log** | What did the code touch, including privileged internal reads? | `ModelPermissionsTable` | exists for writes — [section 6](#6-layer-3-the-data-access-log) |
| **Auth event log** | Who authenticated, as whom, and did it succeed? | `sessions` (no seam exists yet) | new — [section 7](#7-layer-4-the-authentication-event-log) |
| **Action log** | What operation was performed? | `AccessLogInterceptor` | exists, has gaps — [section 4](#4-layer-1-fixes-to-the-access-log) |

A privileged internal read that is never returned to a user is genuinely *not* a disclosure event —
it is a data-access event. Different question, different log. No layer should be stretched to cover
another's job.

The auth event log was originally assumed to be an extension of what `sessions` already records. A
source survey ([section 7.1](#71-survey-findings)) established that `sessions` records auth *state*,
never auth *events* — so it is a fourth layer, and the layer with the least existing foundation.

All four are joined by a single **request ID** ([section 3](#3-layer-0-request-identity)), which is
therefore a hard prerequisite for the rest of the work.

### 2.1 Why the typed layer, and not the database layer, for disclosure

The disclosure hook must be **hard to circumvent by accident**. An engineer adding a new endpoint
should not be able to bypass audit without deliberately working around it.

`ModelPermissionsTable` is the wrong level for this: an endpoint that performs a privileged read and
then returns a derived value to the user would produce a data-access record attributed to no
disclosure, while the actual disclosure goes unrecorded. `ModelRestEndpoints` is worse — it only
covers endpoints that happen to be built from it.

`ApiHttpHandler.handle` is the single choke point every typed endpoint passes through, and it has
the serializer for the outgoing value (`outputType`). Hooking there means a new endpoint is audited
by construction.

**The two layers are complements, not alternatives**, and each covers the other's blind spot:

| | Sees | Cannot see |
|---|---|---|
| Typed layer ([5](#5-layer-2-the-disclosure-log-audited)) | what actually reached a client, field by field | a privileged read that never leaves the server |
| Database layer ([6](#6-layer-3-the-data-access-log)) | every query and mutation, whoever issued it | whether any of it reached a client |

So disclosure is recorded at the typed layer, and *access* — every condition and sort applied to the
data — is recorded at the database layer. Neither alone is sufficient.

### 2.2 Why record disclosed fields rather than masked fields

An earlier version of this design proposed deriving the record from the `Mask` that was applied
(`Mask.pairs` whose condition failed, via `Modification.affectsPaths()`). That was rejected, for two
reasons:

1. It requires knowledge that only exists below the typed layer, reintroducing the circumvention
   problem above.
2. It answers the wrong question. Mask-derived logging records **what was redacted**. Value-derived
   logging records **what was disclosed** — which is the fact that would actually have to be
   testified to.

The typed layer therefore records field *presence*: which fields held non-default values in the
payload the client received. This is measured from the outgoing value, needs no knowledge of
permissions, and directly describes the disclosure.

---

## 3. Layer 0: request identity

A first-class request ID, threaded through every layer. Prerequisite for everything else — without
it the four logs cannot be joined, and four unjoinable logs are barely better than one.

### 3.1 Shape

**Implemented.** The three properties live on the `Request` base class (`core/.../data/Request.kt`),
not on `HttpRequest` alone — `HttpRequest` and `WebSocketConnectRequest` both extend it, so HTTP and
WebSocket connections carry identity uniformly and [3.4](#34-non-http-entry-points) falls out for
free rather than needing a parallel mechanism.

The shape below is as built, on both subclasses:

```kotlin
@Serializable
public data class HttpRequest<PATH : PathSpec>(
    public val requestId: String,
    public val parentRequestId: String? = null,
    public val upstreamRequestId: String? = null,
    override val path: RawHttpEndpoint<PATH>,
    // ... existing properties
)
```

- `requestId` — **authoritative**, always generated or accepted by the engine. No default: an engine
  that forgets to supply one fails to compile rather than silently producing unattributable audit
  records. This is a source-compatibility break for anything constructing `HttpRequest` directly,
  which is intended — engines are the only legitimate constructors. The `TestRunner` helpers take
  `requestId: String = generateRequestId()`, so tests get one for free but can pin it when asserting
  correlation.
- `parentRequestId` — set for sub-requests dispatched inside a multiplexed request (see
  [section 4.1](#41-metabulk-bypasses-the-entire-interceptor-chain)).
- `upstreamRequestId` — informational only, see the security note below.

`copyWithNewPathType` gained the corresponding parameters and preserves identity unchanged. Deriving
a *new* logical request is a separate, explicitly-named operation so it cannot happen by accident:

- `HttpRequest.subRequest(...)` — fresh `requestId`, `parentRequestId` set to the outer request. Used
  by the `/meta/bulk` dispatcher.
- `WebSocketConnectRequest.subConnection(...)` — the same, for multiplexing. Used by
  `MultiplexWebSocketHandler`, where each channel is a distinct logical socket.

`QueryParamWebSocketHandler` deliberately does **not** use `subConnection`: it rewrites the path of
the same physical socket rather than opening a new logical one, so identity carries over unchanged.

**Operational note for AWS serverless.** `WebSocketConnectRequest` is persisted to DynamoDB
(`AwsWebSocketDynamoDb.kt:219`), so making `requestId` required means rows written before the upgrade
fail to deserialize. In-flight WebSocket connections are dropped on the deploy that picks this up and
clients reconnect. This was chosen over a nullable field or a generated default: a default would mint
a different ID each time a connection's state was loaded, which is worse than a reconnect because it
silently breaks correlation for the whole life of the socket.

### 3.2 Sourcing, and the trust rule

**Never trust a client-supplied request ID.** If an arbitrary caller can set `X-Request-Id`, they can
forge or collide IDs to splice their own actions into another principal's trace, or to poison
correlation across all three layers. That is a direct attack on the integrity of the audit system
itself, so it has to be closed by construction rather than by convention.

**Implemented** as `HttpHeaders.requestIdentity(trustedRequestIdHeader, onTrustedHeaderMissing)` in
`core/.../http/RequestIdentity.kt`. Resolution order:

1. If `trustedRequestIdHeader` is configured and present, adopt it as `requestId`.
2. Otherwise generate one, and invoke `onTrustedHeaderMissing` if the header was configured but
   absent — a misconfigured or bypassed proxy degrades correlation rather than failing the request,
   but is reported.

Any client-supplied `X-Request-ID` that was not the trusted value is recorded as `upstreamRequestId`
and never used for correlation. When the trusted header *is* `X-Request-ID` (the Envoy arrangement)
there is no separate untrusted claim, so `upstreamRequestId` stays null.

"Trusted hop" is explicit configuration, never header sniffing: `requestIdHeader` on
`KtorRuntimeSettings`, `NettyRuntimeSettings`, and `JdkRuntimeSettings`, defaulting to null so the
out-of-the-box behaviour is to trust nothing. It deliberately mirrors the existing `realIpHeader`
setting, which solves the same trust problem for source IP.

**AWS serverless needs no such setting either, but for a different reason.** An earlier version of
this plan had the adapter adopt `requestContext.requestId` (and `requestContext.connectionId` for
WebSockets) as the authoritative id. That was abandoned when request ids became `Uuid`s: API Gateway
ids are not UUIDs and not even 128 bits, so there is nothing to adopt. The adapter now mints its own
`Uuid` on both paths.

The join to the gateway's own access log is preserved rather than lost. The gateway's id is kept in
`RequestRecord.engineRequestId` — trusted, because the engine supplied it rather than the caller, and
therefore deliberately a separate column from the untrusted `upstreamRequestId`. The join is:
gateway log `requestId` → `engineRequestId` → our `Uuid`. For WebSockets the connection id already
lives in `engineSocketId` and is surfaced through the same property, so a socket keeps one identity
for its whole lifetime without storing the value twice.

See [`execution-context-refactor.md`](execution-context-refactor.md) §2.6.

### 3.3 Aligning with the proxy and with telemetry

Envoy generates `x-request-id` and propagates W3C `traceparent`. If the deployment's proxy stamps the
same ID that it forwards, the external capture layer and the in-process logs join for free — no
separate correlation step, no clock-skew matching. Reusing the trace ID also gives correlation with
the existing `TelemetryTrace` at zero cost.

This is the trusted-hop case from 3.2, so it is compatible with the trust rule.

### 3.4 Non-HTTP entry points

Correlation gaps land exactly where the unlogged disclosure channels are, so these are not optional:

- **WebSocket connections**: a `connectionId` established at connect. Each inbound message and each
  outbound push gets its own ID parented to the `connectionId`.
- **Scheduled tasks and background jobs**: generated at dispatch. If a task was enqueued by a
  request, `parentRequestId` carries that request's ID.

---

## 4. Layer 1: fixes to the access log

These are correctness bugs affecting security controls well beyond logging, and they should be fixed
first because the disclosure log inherits the same dispatch paths.

### 4.1 `/meta/bulk` bypasses the entire interceptor chain

`MetaEndpoints.kt:409` dispatches sub-requests by calling the handler directly:

```kotlin
(properRequest.path.match.value as HttpHandler<PathSpec>).handle(properRequest)
```

This never routes through `compiledHttpInterceptors`. Every sub-request therefore escapes **all**
interceptors — not just access logging, but CORS, rate limiting, compression, and any future audit
interceptor. N logical requests execute and the pipeline sees one.

**Fixed.** `ServerRuntime.handle` now splits into two layers around routing:

- `handle` runs the connection-scoped chain once for the physical request, then delegates to
- `dispatchLogicalRequest` — logical-scoped chain, route resolution, handler invocation, error
  mapping — which is the single choke point every logical request passes through.

`handleSubRequest` is the public entry a multiplexed endpoint uses to re-enter that choke point, and
`MetaEndpoints.bulk` now calls it instead of invoking the matched handler directly. Sub-requests are
derived with `HttpRequest.subRequest`, so each carries its own ID parented to the outer request.

Two things fell out of the extraction:

- The slash-redirect branch read `request.path.pathSegments` (the outer request) where it meant
  `req.path` — harmless while there was only ever one request in scope, and an outright bug the
  moment sub-requests re-enter. Fixed as part of the move.
- Bulk previously hand-rolled its own exception-to-`BulkResponse` mapping. It now reads the outcome
  off the response the pipeline produces, via `HttpResponse.toLSError()`.

This requires interceptors to distinguish scope, because re-running some of them per sub-request is
wrong. **Scope is carried by the type, not by a property**, so the two cannot be crossed:

```kotlin
/** Shared contract. Not installable — an interceptor is one of the two kinds below. */
public interface HttpInterceptor { /* name, intercept */ }

/** Once per physical request, outside routing. CORS, compression, security headers. */
public fun interface HttpConnectionInterceptor : HttpInterceptor

/** Every logical request, sub-requests included. Access log, audit, rate limiting. */
public fun interface HttpLogicalInterceptor : HttpInterceptor
```

An earlier draft put an `InterceptorScope` enum on a single `HttpInterceptor` type. That was
rejected: it leaves the invariant to convention, so an interceptor could be written for one scope and
silently registered under the other, and nothing would catch it. With two types, `ServerBuilder`
keeps two registries and two `install` overloads resolved at compile time, `ServerDefinition.Module`
carries two lists, and there is no expressible way for an interceptor to end up in the wrong chain.
`HttpInterceptor` survives only as the shared contract and as the type of a compiled chain — it
cannot be installed.

Classification as built:

| Interceptor | Kind | Why |
|---|---|---|
| `GzipInterceptor` | `HttpConnection` | compression applies to the physical body; per-sub-request would double-encode |
| `SecurityHeadersInterceptor` | `HttpConnection` | headers belong to the physical response |
| `CorsInterceptor` | `HttpConnection` | plus `WebSocketInterceptor`, as before |
| `AccessLogInterceptor` | `HttpLogical` | otherwise one line for `/meta/bulk` regardless of contents |
| `RateLimitInterceptor` | `HttpLogical` | otherwise a bulk request of 100 sub-requests costs one unit — a bypass |

**Ordering consequence worth knowing:** interceptors nest by kind first and installation order
second, so every `HttpConnectionInterceptor` wraps every `HttpLogicalInterceptor` regardless of
install order. This only reorders a pair spanning both kinds, and the nesting it produces is the one
you want — but it is a behaviour change for such a pair.

### 4.2 WebSockets are not access-logged at all

`AccessLogInterceptor` implemented only the HTTP interceptor interface. `WebSocketInterceptor`
exists and `CorsInterceptor` already implemented both, so the pattern was established — the access log
simply did not use it.

**Fixed**, and the investigation turned up a second, larger defect behind it.

**`MultiplexWebSocketHandler` bypassed the WebSocket interceptor chain entirely.** All six of its
lifecycle paths resolved the sub-handler as `match.value` — the raw handler from the route table —
instead of passing it through `the WebSocket interceptor chain`. Every virtual socket inside a
multiplexed connection therefore escaped *every* WebSocket interceptor: access logging, rate limiting,
CORS. This is the same defect `/meta/bulk` had on the HTTP side
([4.1](#41-metabulk-bypasses-the-entire-interceptor-chain)), in the same shape — many logical
connections executing while the pipeline saw one — and it was not visible from the access-log symptom
that led here. All six sites now intercept.

`AccessLogInterceptor` implements both interfaces and logs connect and disconnect (with close
reason), each carrying the connection ID from [3.4](#34-non-http-entry-points). Because virtual
sockets now pass through the chain, they are logged too, with the physical connection as parent.

#### 4.2.1 WebSocket interceptors are split by scope, as HTTP interceptors are

Routing virtual sockets through the chain created the mirror-image problem: an interceptor would now
run *both* for the physical connection and again for each virtual socket inside it. For a
connection-scoped concern that is wrong — CORS would re-decide an origin question about a request
that never crossed the network.

So `WebSocketInterceptor` is split the same way, and for the same reason, as
[4.1](#41-metabulk-bypasses-the-entire-interceptor-chain) split the HTTP side:

```kotlin
/** Shared contract. Not installable — an interceptor is one of the two kinds below. */
public interface WebSocketInterceptor { /* name, intercept */ }

/** Once per physical socket the client opened. Origin checks, transport policy. */
public interface WebSocketConnectionInterceptor : WebSocketInterceptor

/** Every logical socket, virtual ones included. Access log, audit, rate limiting. */
public interface WebSocketLogicalInterceptor : WebSocketInterceptor
```

`ServerBuilder` keeps two registries and compile-time-resolved `install` overloads, so an interceptor
cannot reach the wrong chain — the same guarantee, by the same mechanism, as the HTTP kinds.

Composition lives in one place, `ServerDefinition.interceptIncomingSocket`: connection-scoped
outside, logical-scoped inside. The physical socket is itself a logical socket, so it gets both; a
virtual socket gets only the logical chain. That parallels HTTP exactly, where `handle` applies both
chains and `handleSubRequest` applies only the logical one.

| Interceptor | Kind | Why |
|---|---|---|
| `CorsInterceptor` | `WebSocketConnection` | decides about the origin of the one real socket |
| `RateLimitInterceptor` | `WebSocketLogical` | else a multiplexed connection is one unit however many sockets it carries |
| `AccessLogInterceptor` | `WebSocketLogical` | a line per logical socket, not one per client connection |

Connection-scoped WebSocket interceptors have only CORS today. The kind exists anyway, so the
distinction is settled at the type level before something needs it — the invariant is pinned by a
test either way.

### 4.3 The access log records too little, too early

It logged *before* calling `cont(request)`, so a line carried no status and no duration, and a request
that died mid-handler read as a clean "accessed".

**Fixed.** The line is emitted after `cont` returns, inside a `finally` so a handler that threw still
produces one — marked with its status, or `failed` if nothing came back at all. An access log with
silent gaps is worse than one that records the failure. Duration and the correlation IDs from
[section 3](#3-layer-0-request-identity) are included, with the parent ID rendered for sub-requests
and virtual sockets so a line can be tied back to the request that carried it.

**Correction to an earlier claim in this document.** An earlier draft also asserted that the early
snapshot meant "a masquerade established inside the handler is attributed to the wrong actor". That is
wrong, and no fix was needed for it: masquerade is resolved from the `X-Masquerade` header inside
`Authentication.CacheKey.calculate` and memoized per request, so it is a property of the request
rather than something a handler establishes. Reading the principal before or after yields the same
value. The real reasons to emit late are outcome and duration.

---

## 5. Layer 2: the disclosure log (`@Audited`)

The layer that delivers the actual compliance value.

**Status: implemented.** `:audit`, `:audit-shared`, and `TypedOutputInterceptor` in `core`. Turned on
by including the `DisclosureAudit` module — `path.path("audit") include DisclosureAudit(database)` —
and nothing audits until it is. It is an ordinary [ServerBuilder] module like `ModelRestEndpoints`,
which is what it should be: everything it registers (tables, pre-deploy tasks, interceptors) is what
a module carries, and mounting it at a path namespaces its pre-deploy tasks instead of scattering
them at the root.

### 5.1 Marking

```kotlin
@SerialInfo
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Audited
```

One annotation, two scopes. **On a class:** every instance that reaches a client produces its own
[DisclosureRecord]. **On a property:** that field is *itemised* — a bit is reserved for it, and each
disclosure records whether it carried a value.

`@SerialInfo` is not decoration. A plain Kotlin annotation is invisible to a `SerialDescriptor`, and
this whole design walks descriptors rather than reflecting — so that it sees exactly what the
serializer will emit and behaves the same on every target. It also means the marker only has an
effect on `@Serializable` classes, which audited models always are.

#### 5.1.1 Itemising is opt-in; disclosure is not

**Resolved after initially getting this backwards.** The first design itemised every field of an
audited model automatically, on the reasoning that opt-in would let an engineer add a sensitive field
and leave it silently unaudited.

That reasoning was wrong on the facts. Because one row is written per record disclosed
([5.3](#53-record-shape--one-row-per-record-disclosed)), **the disclosure record exists whether or
not any property is annotated** — it is driven by `@Audited` on the *class*. So "who saw this record,
under which request, when" stays completely covered regardless. Field bits are refinement on top:
*which fields were in the payload*. Opt-in makes that refinement coarser by default; it does not make
a disclosure disappear.

The residual risk is narrow and reviewable: someone adds a regulated element to a model already
marked `@Audited` and forgets to mark the property, so "which requests disclosed an SSN" misses it.
That is a code-review concern on a model already flagged sensitive.

Against that, automatic itemising cost real things:

- **Bits, permanently.** Indices are never reused ([5.4](#54-the-field-registry--append-only-and-the-sole-record-of-what-a-bit-means)),
  so a model accumulates dead bits through every rename and removal. Headroom shrinks monotonically
  over a model's life, and nested paths grow faster than property counts. Reserving indices for
  `sortOrder` and `createdAt` spends capacity that never comes back.
- **Signal.** A field-set where forty of forty-five bits are noise makes the interesting query harder
  to write and harder to trust.

Two consequences worth stating so they are not read as bugs later:

- **A disclosure with no bits set is normal and correct** — "this record was disclosed, and none of
  the fields we track were in its payload".
- **`_id` gets no bit.** It is already recorded as `recordId` on every row, so itemising it would
  spend a bit restating the row's own identity.

An annotated property is found anywhere in the graph, including inside types that are not themselves
audited: `@Audited val street: String` inside an `Address` becomes `address.street` on the record
holding it. The walk traverses unannotated properties too — it just allocates nothing for them.

**`@AuditedOperation` was dropped.** It was specified as a class annotation on an endpoint, but
endpoints in this framework are not classes — they are `ApiHttpHandler(...)` factory calls producing
a private data class, so there is nothing to annotate. Auditing non-disclosure operations is
deferred; if it returns it belongs as a parameter on the handler factories, not as an annotation.

**The model must be keyed by `Uuid`.** One disclosure row is written per record disclosed, which
makes the identifier the most-written column in the system. A `Uuid` is sixteen bytes in every
backend; a string key is larger and indexed poorly. Marking a class whose `_id` is absent or is not a
`Uuid` fails at deploy.

### 5.2 Interception: a new typed-output layer

Disclosure cannot be observed from an `HttpInterceptor`: by the time one sees a response it is
bytes, and no statement about *which fields* a client received is possible any more. So there is a
third interception system alongside the HTTP and WebSocket ones:

```kotlin
public interface TypedOutputInterceptor {
    public val name: String get() = this::class.simpleName ?: "anonymous"

    context(runtime: ServerRuntime)
    public suspend fun <T> outputProduced(request: Request<*>, serializer: KSerializer<T>, value: T)
}
```

Installed on `ServerBuilder` like any other interceptor and exposed as
`ServerDefinition.typedOutputInterceptors`. Unlike the other two it is a flat list rather than a
compiled chain: these observe, they do not wrap, so there is nothing to short-circuit and no
order-dependent post-processing. `emitTypedOutput` returns immediately when nothing is installed, so
a server that does not audit pays nothing per response.

**Observation only.** An implementation may not alter the value. It *may* throw, and throwing aborts
the send — which is how [5.6](#56-failure-behaviour-fail-closed) is enforced.

**Installation is explicit, and that is the right granularity.** Nothing audits until a deployment
includes the `DisclosureAudit` module, and likewise nothing records conditions until it installs the
table interceptor ([6.1](#61-extend-it-to-reads-every-condition-and-every-sort)). `@Audited` on a
model does nothing on its own. The circumvention this design guards against is an *endpoint* built
without auditing, not a deployment that chose not to audit — and once installed, no endpoint can
escape either interceptor. A per-deployment switch is a decision made once, in the open; a
per-endpoint one would be made a hundred times, silently.

#### Coverage

Every typed output funnels through exactly two places, which is what makes "no exceptions"
achievable rather than aspirational:

| Seam | Covers |
|---|---|
| `ApiHttpHandler.handle`, between the handler returning and `toTypedData` | every typed HTTP response, and every `/meta/bulk` sub-response, since those re-enter through `handleSubRequest` |
| `ConnectionWrapper.send`, before `encoder.ws(...)` | every typed WebSocket frame, model update streams and multiplexed sockets included |

**The one inherent exception**: raw `HttpHandler`s and signed file downloads emit bytes with no
serializer. There is no typed value to inspect, so no field-level disclosure statement is possible
there at any layer. This is a property of untyped endpoints, not a gap in the mechanism.

The precomputed per-endpoint audit plan described in earlier drafts is not needed for the seam
itself; it belongs to extraction (not yet built) as an optimisation.

### 5.3 Record shape — one row per record disclosed

**Rejected: grouping by field set.** An earlier draft made the unit a *(request, model, field-set)*
group carrying a list of ids, collapsing a ten-thousand-row query into one or two rows. It was
rejected on the grounds that settle it: this is an audit log, and "most of these records were
disclosed the same way" is not a claim an audit log gets to make. Every disclosure is its own row.

The volume is paid down inside the row instead:

1. **Request-constant data lives once.** IP, principal, endpoint, and outcome are
   properties of the request, recorded once in the layer-1 record and referenced by `requestId`.
   Disclosure records never repeat them. The row's own instant is the exception, and it costs
   nothing: `_id` is a version-7 UUID, so `DisclosureRecord.at` derives the moment of disclosure from
   the key itself. That is worth having here rather than deferring to the request record, because a
   socket's `requestId` names the socket rather than the phase that disclosed
   ([5.8.2](#582-a-sockets-row-is-keyed-by-the-socket-not-by-the-phase-resolved)) — without it, a
   disclosure on a long-lived connection can only be placed "sometime during this session".
2. **The field set is two `Int`s, not four.** Itemising is opt-in ([5.1.1](#511-itemising-is-opt-in-disclosure-is-not)),
   so a model reserves bits for the handful of fields that matter rather than for all of them.
3. **The identifier is a `Uuid`, not a string.** Sixteen bytes in every backend, and indexable.
   A list of stringly-typed ids is both larger and, in most engines, stored poorly — which is why
   [`@Audited` is restricted to models keyed by `Uuid`](#51-marking), enforced at deploy.
4. **No parent request id.** A sub-request's parentage is recorded once in the request log; carrying
   it on every disclosure row would be storing the same join key twice.

```kotlin
@IndexSet(fields = ["modelId", "recordId"], name = "byRecord")
@Serializable
public data class DisclosureRecord(
    override val _id: Uuid,    // v7: `at` derives the disclosure instant from it
    @Index val requestId: Uuid,
    val modelId: Int,          // from the registry
    val fields0: Int,          // bits 0..31   } disclosed-field bitfield; see 5.4 for
    val fields1: Int,          // bits 32..63  } indices, 5.3.1 for why Int columns
    val recordId: Uuid,        // the _id of the record disclosed
) : HasId<Uuid>
```

The two indexes are the two questions an investigation actually asks: *what did this request
disclose* (`requestId`) and *who has seen this record* (`modelId, recordId`).

`modelId` is keyed on the descriptor's **serial name**, not on a table name. A disclosure is
observed with a serializer in hand and nothing else — the table a value came from is not knowable at
that point, and an audited model need not be a table at all.

#### 5.3.1 Why `Int` columns and not `Long`, `ULong`, or `ByteArray`

**Because `Int` is the only type the framework can query bitwise.** The entire bitwise condition
surface is `Condition<Int>`:

| Condition | Semantics |
|---|---|
| `IntBitsClear(mask)` | all mask bits clear — `on and mask == 0` |
| `IntBitsSet(mask)` | all mask bits set — `on and mask == mask` |
| `IntBitsAnyClear(mask)` | at least one mask bit clear — `on and mask != mask` |
| `IntBitsAnySet(mask)` | at least one mask bit set — `on and mask != 0` |

There are no `Long`, `ULong`, or `ByteArray` equivalents. A `ULong` or `ByteArray` bitfield would be
**storable but not queryable** — "which requests disclosed the SSN field?" would require a full scan
and client-side filtering, which is unusable at audit-table volume.

Layout: bit index `i` lives in column `i / 32` at bit `i % 32`. `FieldBits` owns this arithmetic;
`disclosedAll(indices)` and `disclosedAny(indices)` build one condition per column touched and
combine them with `And` / `Or`.

**64 bits is the working ceiling, and two columns is deliberate.** Adding a `fields2` column later
defaults to `0`, which reads correctly for every historical record — absent means not disclosed — so
the migration out is benign. Buying headroom up front would cost eight bytes on every row of the
highest-volume table in the system to insure against a problem that is cheap to fix if it arrives.

#### 5.3.2 Blocking prerequisite: the bitwise conditions were broken in every engine

**Fixed in `service-abstractions`, in two rounds.** Both rounds were live correctness bugs affecting
anyone using bitwise conditions, independent of audit logging.

**Round one — the mask/column confusion.** `SqlFieldSet.single(value)` returns
`(column, maskLiteral)`. Two of four SQL mappings compared against the column where the mask was
intended, and MongoDB had all four All↔Any transposed:

| Condition | Intended | Emitted by SQL + Postgres | |
|---|---|---|---|
| `IntBitsClear` | `col & mask = 0` | `col & mask = 0` | ✓ |
| `IntBitsSet` | `col & mask = mask` | `col & mask = col` | ✗ |
| `IntBitsAnyClear` | `col & mask < mask` | `col & mask < col` | ✗ |
| `IntBitsAnySet` | `col & mask > 0` | `col & mask > 0` | ✓ |

The in-memory `invoke()` implementations were correct, so any test running against an in-memory
table passed while the real database returned different rows. The fix shipped with conformance tests
that run the full truth table against **each real engine**.

**Round two — the sign bit.** Found while testing `FieldBits`, and *not* catchable by round one's
tests, because those compare each engine against the in-memory reference and the reference itself
was wrong:

- `IntBitsAnySet` was `on and mask > 0`. Any mask containing bit 31 is a **negative** `Int`, so
  `on and mask` is negative when the bit is set and the comparison returns false. `disclosedAny` on
  field index 31, 63, 95, or 127 silently matched nothing.
- `IntBitsAnyClear` was `on and mask < mask`, wrong the same way.

Both are now stated as exact negations — `!= 0` and `!= mask` — in the reference and in the SQL and
Postgres emission. MongoDB now passes the mask as a **list of bit positions** rather than a number,
because Mongo rejects a negative numeric bitmask outright.

The lesson is recorded in a new `BitwiseConditionSemanticsTest`: a conformance test that compares
drivers to a reference can only catch a driver that disagrees with the reference, never a reference
that is wrong. The semantics need their own test, stated against a literal definition.

Cassandra's `ConditionNormalizer` negation table is logically correct and needed no change in either
round.

> **Version coordination:** the round-two fix lives in `service-abstractions` and is not yet in a
> release `lightning-server` depends on. Until the version is bumped, field indices 31, 63, 95, and
> 127 are storable but not queryable at runtime.

#### 5.3.3 Partial queries must carry `_id` (resolved)

One row per record means extraction needs each item's `_id`, and a `Partial<T>` can omit it.
Resolved in two places, because one alone is not enough:

- **`ModelRestEndpoints.queryPartial` forces `_id` into the requested field set** for audited models,
  so ordinary use simply works rather than failing.
- **Extraction fails closed** on an audited instance that arrives without an `_id`. `ModelRestEndpoints`
  is circumventable — a custom endpoint can call `findPartial` directly — so the guarantee has to live
  at the seam. The first is convenience on the common path; this is the actual guarantee.

### 5.4 The field registry — append-only, and the sole record of what a bit means

A bitfield keyed to declaration order is fragile: inserting or reordering a property shifts every
bit, and all historical records silently change meaning. Storing a schema version per record fixes
correctness but adds a lookup to every read and a version bump to every model change.

**Use an append-only registry instead**, held in the database. Each field of each audited model is
assigned a permanent bit index the first time it is seen; indices are never reused and never shift.

```kotlin
@Serializable
public data class AuditModelRegistration(
    override val _id: String,   // descriptor serial name
    val modelId: Int,
) : HasId<String>

@Serializable
public data class AuditFieldRegistration(
    override val _id: String,   // "$modelId/$fieldPath"
    val modelId: Int,
    val fieldPath: String,
    val bitIndex: Int,
) : HasId<String>
```

Assignment runs as a **pre-deploy task**, once per deploy rather than at startup, so instances never
race to allocate the same index. It is convergent: existing assignments are never altered, so
re-running it is a no-op. The snapshot is loaded once per process, which is correct because
assignments only change during a deploy.

Consequences:

- Bit N means the same field forever. Historical records stay readable with no version lookup.
- Adding or reordering fields never invalidates existing records.
- A per-record schema reference becomes unnecessary, saving bytes in the highest-volume table.

**A rename allocates a new bit, and that is the whole story.** An earlier draft proposed an
`@AuditRetired` marker plus a startup check that failed loudly when a registered field vanished from
the descriptor. That was rejected, correctly: the registry table *is* the retirement record. A field
that disappears simply stops being written; its row remains, so records written before the rename
still resolve to the field they actually disclosed. Nothing that was written in the past changes
meaning, which is the only property that matters. The cost is that a query for a semantic field
after a rename must consider both bits — a query-time concern, not a correctness one.

#### 5.4.1 Nested fields

Bit indices are assigned to **dotted paths**, not property names. The walk descends through
everything, but only annotates what was asked for:

| Shape | Rule | Example path |
|---|---|---|
| Property marked `@Audited` | a path | `ssn` |
| Property not marked | no path, but still descended into | — |
| Nested `@Audited` class | **stop** — it produces its own record under its own `modelId` | `doctor` |
| List/Set | descend into the element with `[]` | `phones[].number` |
| Map | descend into the value with `{}` | `tags{}.label` |
| Sealed | descend per subclass | `payment(Card).last4` |
| Open polymorphic, contextual | nothing beneath — the concrete type is not known statically | `blob` |

Descending regardless of annotation is what lets `@Audited val street: String` inside a plain
`Address` become `address.street` on the record holding it, without `Address` itself having to be
audited or `address` itself having to be annotated.

Annotating a container *as well as* its leaves is what distinguishes "no address was disclosed" from
"an address was disclosed, all of whose fields held defaults" — worth a bit when that distinction
matters, and skippable when it does not.

Three failures fall out, all deliberate:

- A model that runs out of bits **fails at deploy**. The message names how many of the 64 are already
  assigned and says that indices are never reused, so renamed and removed fields still hold theirs —
  which is usually the surprising part. The remedies are to drop `@Audited` from properties that do
  not need itemising, or to mark a nested *entity* type `@Audited` so it becomes its own record.
  A **warning fires at 75% of capacity**, because running out at deploy time is the worst moment to
  discover it and the ceiling is approached gradually rather than all at once.
- An `@Audited` class with no `Uuid` `_id` **fails at deploy** — a disclosure record that can name no
  record is close to worthless, and a string key is too wide for this table.
- An audited model reaching the encoder with no registry entry **fails the request**. Registration
  scans every endpoint input/output descriptor, which covers everything but a contextual serializer
  resolving to an unregistered audited type. That case should be loud, not silent.

### 5.5 Field presence semantics

"Disclosed" means the field held a non-default value in the payload the client received. Defaults
(null, zero, empty string, empty collection) read as not disclosed.

This is deliberately a statement about the *payload*, not about permissions: it is the disclosure
question, and it is measurable at the typed layer without reaching below it. The documented
consequence is that a field whose true value equals its default is indistinguishable from a masked
one. That is acceptable — in both cases the client learned nothing beyond the default.

### 5.6 Failure behaviour: fail-closed

If the audit write fails, the request fails. For audited models the disclosure must not happen unless
it was recorded. This is why the interception point sits *before* serialization: throwing there
prevents the body from ever being built.

**Confirmed to include the database sink.** The queryable log is a database table, so an audit
database outage is an outage for audited endpoints. That is the intended trade.

### 5.7 Integrity: out of scope here

Hash chaining and tamper-evidence belong to the **emergency total-log**, a separate system outside
Lightning Server. It exists for the case where these logs are later found insufficient or bypassable;
it is not a component of this design and nothing in this repository implements it.

An earlier revision of this plan specified an in-process chain, and one was built and then removed. It
is worth recording why, because the reasoning generalises: an in-process chain with an unkeyed hash
and no external anchor cannot resist anyone who can write its table, so it could never have been the
tamper-evidence it was named for. What it did instead was add a second write to the path of every
audited read — including privileged internal ones — which meant a hash failure could halt a schedule
tick or a startup task. It bought a property it could not actually provide, at a real availability
cost. The guarantee belongs outside the process, where it can be held by something the operator does
not control.

### 5.8 The request record — what `requestId` points at

Every disclosure references a `requestId` and repeats nothing else, so a table holding what that id
*means* is not optional; without it the reference dangles.

`AccessLogInterceptor` cannot be that table. It writes log lines, and it is deliberately fail-open —
"the access log must never be the reason a request fails" — which is the opposite of what a
fail-closed log needs from its referent. The audit package carries its own:

```kotlin
@Serializable
public data class RequestRecord(
    override val _id: Uuid,            // the execution id itself: no extra column, join on the PK
    val parentRequestId: Uuid? = null,
    val rootExecutionId: Uuid,         // the head of this row's causal chain
    // no `at` column — see below
    val principal: String? = null,
    val sourceIp: String,
    val endpoint: String,              // the route pattern, not the literal target
    val method: String,
    val outcome: String? = null,       // null while the request is still in flight
    val durationMs: Long? = null,
    val engineRequestId: String? = null, // the gateway's own id, for joining back to its logs
    val upstreamRequestId: String? = null,
) : HasId<Uuid>
```

A duplicate `_id` **fails the request**. A repeated trusted request id means a misconfigured proxy is
about to merge two principals' activity under one identifier, which is exactly what
[3.2](#32-sourcing-and-the-trust-rule)'s trust rule exists to prevent, so it must be loud.

**The timestamp is in the id, not a column.** Every execution id is a version-7 UUID minted at the
instant the execution began (see `execution-context-refactor.md` §2.6), so `_id` embeds its own mint
time and `RequestRecord.at` is a derived property that recovers it. This is why the id is v7 at all:
it orders the append-mostly log by insertion time (range queries over the PK instead of an indexed
copy) and keeps the row's "when" permanently consistent with its id. The price is whole-millisecond
precision only. Ids that were *adopted* from a trusted proxy (which may be any version) carry no
timestamp and degrade to the epoch rather than misreporting — honest about "unknown."

#### 5.8.1 Write ordering (resolved)

Disclosures are written *during* a request, but outcome and duration are only known at the end, so
the ordering has to be chosen rather than assumed.

**Resolved: write the record at request start, update it at completion.** Two writes per request.
The referent always exists before anything references it, and the same shape works for a WebSocket,
where the record is written at connect and updated at close. Requests in flight are visible for free.

Rejected: buffering disclosures and writing everything at the end. It is tempting for HTTP, where the
response is not sent until the end anyway — but it is wrong for WebSockets, whose frames are
delivered as they are sent and whose connections can live for hours, leaving delivered data
unrecorded in memory. A per-protocol split was not worth two code paths.

Disclosures within one request batch into a single `insert`, so the per-request cost is two writes
plus one.

#### 5.8.2 A socket's row is keyed by the socket, not by the phase (resolved)

Since the [execution-context refactor](execution-context-refactor.md), each of a WebSocket's five
lifecycle phases is a separate execution with its own `executionId` — on a serverless engine they are
literally separate invocations. The record for a socket is nonetheless keyed by `Initiator.WebSocket.socketId`.

It has to be, for [5.8.1](#581-write-ordering-resolved) to work at all: the row is written at connect
and updated at close, and those are two different executions. A row keyed by either one's
`executionId` could not be found by the other, and every socket record would sit at `outcome = null`
forever.

**What that gives up.** `messageFromClient` and `messageFromSubscription` are executions that can
disclose. With socket-keyed rows their disclosures point at the socket, so the audit answer for a
long-lived connection is "sometime during this session" rather than "in response to this message".

**Why it is not a regression.** Before the refactor a socket's correlation id was deliberately
constant for the socket's whole lifetime, so disclosures on a socket already attributed to the
session rather than to a frame. Socket-keying reproduces that exactly.

**Resolved: keep socket-keyed rows, and carry the execution id on the records instead.**

The question was whether per-phase attribution justified a `RequestRecord` row per phase execution —
for a chatty socket, a row per client message, against a fail-closed write path. It does not, because
the precision that was actually wanted can be had without those rows. Both record types written
during a socket's life now carry `executionId` alongside `requestId`:
[`DataAccessRecord`](#62-record-shape-and-installation-resolved) from the outset, and any record that
needs it can follow. `requestId` still names the socket, so the join to the request record is
unchanged and one row per socket remains; `executionId` names the phase, so "which message caused
this" is answerable by reading the record rather than by multiplying request records.

`DisclosureRecord` does **not** yet carry it, and that is the remaining gap: a disclosure on a
long-lived socket can still only be placed within the session, though its own v7 `_id` now timestamps
it to the millisecond, which in practice identifies the message. Adding the column is 16 bytes on the
highest-volume table in the system and should be weighed against that.

### 5.9 Sinks

The audit stream is a typed event stream with pluggable sinks, not a single log. Auditors need to
*query*; an encrypted object store is unqueryable by design. Expect at minimum a queryable sink
(Postgres or a SIEM) for investigation alongside the tamper-evident total-log as system of record.

**Shipped:** the database sink, written by `DisclosureLogInterceptor` straight into the
`DisclosureRecord` table. It catches nothing, so an extraction that cannot resolve a model, a record
with no `_id`, or a sink that will not take the write all abort the send before the value is
serialized.

### 5.10 How extraction works

`DisclosureExtractor` walks the outgoing value with its own serializer, as a custom `Encoder`. Using
the serializer rather than reflection means what is observed is exactly what the client will receive,
and that a model cannot slip through a shape the walk does not understand.

Three things are worth knowing about it:

- **Field presence is judged on the value, not on the format.** A default is a default whether or not
  the encoder in use would have elided it, which keeps [5.5](#55-field-presence-semantics) true
  regardless of content negotiation.
- **Paths are resolved once per (model, position, descriptor) and cached.** A list of ten thousand
  records enters the same descriptor at the same path ten thousand times; without the cache every
  field of every record would rebuild its path string and hash it against the registry.
- **`Partial` is unwrapped explicitly.** `PartialSerializer` builds a descriptor named for `Partial`
  that carries none of the model's class annotations, so `@Audited` is invisible on it. The extractor
  reads `PartialSerializer.source` instead. Without that, a partial query would have disclosed an
  audited model with no record at all — the exact hole this design exists to close.

---


## 6. Layer 3: the data access log

Write auditing already exists at `ModelPermissionsTable`. This layer stays where it is, and its
scope is now explicit: it answers "what did the code touch," including privileged internal reads that
never reach a user.

One architectural advantage worth exploiting: `Modification<T>` is a first-class serializable value
in this stack. Logging `(condition, modification, affected ids)` is far more compact than before/after
images and strictly more informative about intent.

### 6.1 Extend it to reads: every condition and every sort

**This is what closes the aggregation hole, and it is a prerequisite rather than a refinement.**

`groupCount` and `groupAggregate` return `Map<String, _>` **whose keys are field values**.
`groupCount(condition = Always, groupBy = ssn)` returns the distinct SSNs — not an inference channel
but a bulk read, and one that produces no disclosure record at all, because there is no record to
attach it to. `count` and `aggregate` are oracles in the ordinary way: `count(ssn eq "X")` answers
1 or 0.

Restricting aggregation does not close this. **`find` is the same oracle** — `find(ssn eq "X")`
returning nothing discloses nothing under the field-presence rule ([5.5](#55-field-presence-semantics))
and tells an attacker the same bit. A sort is an oracle too: ordering plus `skip` walks values
without ever matching one.

**Resolved: record the `Condition<T>` and the sort for every query against an audited model**, at the
database layer, alongside the mutation recording that already lives there. Aggregation then stops
being a special case — `groupCount` passes its condition and its group-by path through the same
choke point as everything else, and a binary search appears as thousands of recorded queries whose
conditions walk a value: detectable and attributable.

The database layer is the right level for this specifically because it is *not* the typed layer. It
sees every query whoever issued it, including the privileged internal reads the typed layer never
observes — see the table in [2.1](#21-why-the-typed-layer-and-not-the-database-layer-for-disclosure).

**Known gap, accepted:** recording a condition records that a bulk read happened, not what came back.
For `groupCount` by a sensitive field the log says "someone enumerated this field", without the
values or any record ids — because by construction there are none to name. A deployment that cannot
accept that should deny the grouping through permissions rather than expect the framework to forbid
it.

Two things that effort has to settle:

- ~~**`requestId` must be reachable from the database layer**~~ — **no longer a prerequisite, and the
  proposed signature change is unnecessary.** Since the execution-context refactor a `ServerRuntime`
  carries `initiator.executionId`, and every seam below already runs in one. Nothing needs to travel
  with the table.
- **The insertion point already exists and is unused.** `ModelInfo` declares a `log` decorator slot
  (`typed/.../ModelInfo.kt:103`):

  ```kotlin
  log: context(ServerRuntime) AuthAccess<USER>?.(Table<T>) -> Table<T> = { it },
  ```

  It defaults to identity, nothing in either repository passes it, and it is applied in **both**
  `table(auth)` and `table()` — so a decorator installed here sees the privileged internal reads that
  [2.1](#21-why-the-typed-layer-and-not-the-database-layer-for-disclosure) requires this layer to
  cover, as well as user-facing ones. It sits below permissions, which is where a recorder of
  *attempted* access belongs.

  Consequence for sizing: this is a `Table` decorator in `lightning-server`, wrapping the read and
  write methods and recording `(condition, sort, modification)`. **No `service-abstractions` change
  is required** — `Table` is the interface, and the decorator lives on this side of it. What is still
  unspecified is the record shape.
- **Scope and failure mode.** Recording every query on every model would be a firehose; this should
  be scoped to audited models, and should be fail-closed there for the same reason disclosure is.

Operations that are neither model reads nor model writes rely on the action log for now, since
`@AuditedOperation` was dropped ([section 5.1](#51-marking)).

### 6.2 Record shape and installation (resolved)

```kotlin
@Serializable
public data class DataAccessRecord(
    override val _id: Uuid,               // v7: `at` derives the query's instant from the key
    @Index val requestId: Uuid,           // joins to RequestRecord, socket-keyed like a disclosure
    @Index val executionId: Uuid,         // the precise execution, which requestId blurs for sockets
    @Index val modelId: Int,              // same registry as DisclosureRecord
    val operation: DataAccessOperation,
    val condition: String,                // serialized Condition<T>
    val sort: String? = null,             // serialized List<SortPart<T>>, where the operation takes one
    val modification: String? = null,     // serialized Modification<T>, for writes
    val groupBy: String? = null,          // the field path an aggregation grouped on
) : HasId<Uuid>
```

**Why the query is stored as text.** `Condition<T>` and `Modification<T>` are serializable, but only
against the model's own serializer, and this table holds rows for every audited model at once. A
generic record type would mean a table per model. The condition is serialized with the model's
serializer at write time and stored as JSON, which stays readable and greppable and gives up only
the ability to query *inside* a recorded condition — an investigation reads these rows, it does not
join on their contents.

**Both ids are recorded.** `requestId` matches [5.8](#58-the-request-record--what-requestid-points-at)
so a query joins to the same request record as a disclosure — which for a socket names the socket.
`executionId` is the phase that actually issued the query. Carrying both costs 16 bytes and recovers
the per-phase precision [5.8.2](#582-a-sockets-row-is-keyed-by-the-socket-not-by-the-phase-resolved)
gives up, without a `RequestRecord` row per phase.

**Installation is the `log` slot, and scope is the registry.** The decorator is passed as
`ModelInfo`'s `log` parameter (see 6.1), and no-ops for any model the audit registry does not know —
so passing it uniformly is safe, and only audited models generate rows. This keeps the "which models
are audited" decision in exactly one place, the `@Audited` annotation, rather than splitting it
between the annotation and a list of decorated tables.

**An audited model needs an endpoint before it can be logged here — a real limitation.** Model ids
are assigned by scanning **endpoint serializers**, never tables (see [`DisclosureAudit`]'s reasoning:
scanning tables made disclosure coverage look complete when it was not). The decorator keys off the
`@Audited` annotation and then resolves the id, which *throws* when there is none. So an audited model
that no endpoint's serializer can reach has no id, and its reads fail rather than going unrecorded —
correct as a fail-closed rule, but it means a model that is only ever read internally cannot be
data-access-logged until something makes it reachable. Resolving this properly means either a second
registration space for table-only models or an explicit opt-in list on the module; **not decided**.

**Fail-closed, with the same reasoning as 5.6.** A read of an audited model whose query cannot be
recorded does not happen. That makes an outage of the audit database an outage for reads of audited
models, which is a strictly larger blast radius than the disclosure log's — that one only fails
requests that actually disclose, while this one fails privileged internal reads too, including ones
made during startup or a schedule tick. **This is the single riskiest thing in this plan** and is
called out again in the deployment notes.

---

## 7. Layer 4: the authentication event log

**This is a fourth layer, not an extension of an existing one.** A survey of the `sessions` module
(2026-08, findings below verified against source) established that it records **no auth events at
all**. What it has is mutable *state*, not an event history — and state cannot answer the questions
auditors ask about authentication.

### 7.1 Survey findings

The distinction that matters: a `Session` row records that a session *exists*, not that a login
*happened*. Everything found falls into one of three buckets — last-write-wins state on a row,
an ephemeral cache counter with a TTL, or a debug `println`.

**Nothing is logged.** `sessions/src/main` contains zero `logger.` calls. Auth failure reasons go to
stdout via `println`, gated on debug (`SessionManager.kt:357-388`). WebAuthN verification failures go
to stderr via `printStackTrace()` (`WebAuthNProofEndpoints.kt:444`).

**Events with no record whatsoever:**

| Event | Current state |
|---|---|
| Failed login | Not persisted, not logged. Only an ephemeral cache counter, **deleted on next success** (`Cache.ext.kt:99-104`) |
| Access-token issuance | Nothing written (`SessionManager.kt:432-442`) |
| Masquerade (successful) | Nothing — `Authentication.kt:294` returns immediately. Only *denials* are logged (`Authentication.kt:296`) |
| Email/SMS PIN proof | Nothing; PIN state lives only in cache and is deleted on success (`PinHandler.kt:70-73`) |
| Permission/scope changes | Nothing. `Session.scopes` is fixed at creation (`sessionModels.kt:67`) |
| Password/TOTP/WebAuthN proof failure | Nothing beyond the rate-limit counter |

**Events recorded only as overwritten state:** refresh-token use unions into `Session.ips`/
`userAgents` and overwrites `lastUsed` (`SessionManager.kt:396-410`); proof use overwrites
`lastUsedAt` on the secret row. These are *sets and single timestamps* — no ordering, no per-use
record, no failure/success distinction.

**Three findings that are actively adverse to audit, and are arguably bugs in their own right:**

1. **Session rows are hard-deletable.** `update = isRoot, delete = isRoot`
   (`SessionManager.kt:188-189`), exposed over REST (`AuthEndpoints.kt:484-485`) — despite comments
   at `SessionManager.kt:83` and `:523` claiming rows are "kept for audit trail". A super-user can
   erase them, and nothing records that they did.
2. **Backup-code use destroys its own evidence.** The row is hard-deleted on use
   (`BackupCodeEndpoints.kt:200`), and `BackupCodeSecret` has no timestamp fields at all
   (`BackupCodeEndpoints.kt:36-41`). After a backup code is used there is no trace it ever existed.
3. **`Session.ips` records a literal `"test"`** when the source IP is absent, and `""` for a missing
   user agent (`SessionManager.kt:398-399`) — placeholder values silently entering what is currently
   the closest thing to an auth audit trail.

**Masquerade is entirely request-scoped.** It lives only in `Authentication.fromMasquerade`
(`Authentication.kt:132`), computed per-request from the `X-Masquerade` header
(`Authentication.kt:240-304`), persisted nowhere, and absent from `JwtClaims` entirely
(`JwtModels.kt:8-22`). There is no start/stop pair to record even in principle — it is header
presence or absence per request. The actor-vs-target pair is constructed at
`Authentication.kt:282-292` and discarded when the request ends.

### 7.2 No usable extension point exists

One must be added. `SessionManager.kt:649-652` already carries a TODO asking for exactly this
("Add hooks for session lifecycle events (created, used, expired, terminated) — useful for audit
logging"). Why each apparent seam fails:

- **Write paths are sealed.** `newSession` is not `open` (`SessionManager.kt:276`);
  `terminateSessionById` is `private` (`:326`); `RefreshToken.session` is `private` (`:355`).
- **`sessionInfo` cannot be wrapped.** `ModelInfo` supports a `signals` wrapper (`ModelInfo.kt:52`),
  used elsewhere for hashing, but `sessionInfo` is built by `explicitModelInfo` with no `signals`
  argument and is a non-open `val` (`SessionManager.kt:165-192`). So the layer-3 approach — decorate
  the table — is unavailable here. **This is the smallest change that would unlock a large part of
  this layer** and should be considered first.
- **Policy predicates carry no context.** `permitAuthentication` (`SessionManager.kt:121`) receives
  only the subject — no `Request`, so no IP or user agent, and it cannot distinguish an attempt that
  later fails.
- **The access log structurally cannot see failed logins.** The `login`/`login2`/`prove` endpoints
  are `noAuth` and throw before any auth resolves (`AuthEndpoints.kt:288, :333, :392`).

### 7.3 Design

Auth events are their own record type, sharing the request ID, the total-log's integrity guarantees (5.7), and
sinks (5.9) with the disclosure log — but not its shape, since they are not model disclosures.

Minimum event set, each carrying request ID, timestamp, source IP, user agent, outcome, and both
acting and target principal where they differ:

- login success / **failure with reason** / logout
- token issuance, refresh, and refresh failure
- session termination, including the acting principal when terminated by an administrator
- proof submission per method, success and failure
- masquerade assumed and released, with actor and target
- scope or permission change

Two ordering notes for implementation. **Failure reasons already exist** as the `println` strings at
`SessionManager.kt:357-388` — that is a direct map to the failure-reason enum, not new analysis.
And **`sessionInfo` gaining a `signals` parameter is the cheapest first step**, since it makes
session creation, update, and termination observable without touching sealed methods.

#### 7.3.1 The seam, and one deliberate asymmetry

`AuthEventReporter` lives in `core` rather than in `sessions` or `audit`, because those two do not
depend on each other and should not have to: `sessions` raises events, an audit module installs a
reporter that records them, and a deployment with no reporter installed pays a null check. Events are
passed as strings so `core` does not own the taxonomy.

**Reporters must not throw, and that is a real weakening.** The disclosure and data access logs gate
*disclosure* — the guarded thing must not happen unless it was recorded, so they fail closed. An
authentication event has already happened by the time it is reported, and is usually reported from a
path that is itself rejecting something; throwing there would replace a clean "your login failed"
with an unrelated server error and lose the original reason. So `AuthEventLogReporter` logs and
swallows write failures. The consequence, stated plainly: **an attacker who can make the audit
database unavailable can make authentication events go unrecorded while authentication keeps
working.** That is the opposite of the guarantee the other two layers give, and it is a choice, not
an oversight.

**Coverage so far is partial.** The seam exists and rejected authentications report through it. The
remaining events in the list above — issuance, refresh, termination, per-method proof results,
masquerade — are not yet raised; the reporter will record them as soon as the call sites do.

The three adverse findings in 7.1 should be fixed alongside: session rows want `delete =
Condition.Never` with soft-termination only, backup-code use wants a soft-disable with a timestamp
rather than a hard delete, and the `"test"` IP placeholder wants to fail rather than fabricate.

## 8. WebSocket permission staleness

Independent of audit, this is a live permissions bug and should be fixed alongside.

`ModelRestUpdatesWebSocket` snapshots the mask at connect time
(`ModelRestUpdatesWebSocket.kt:65`, `mask = info.table(access).mask()`) and stores it in
`ModelRestUpdatesWebSocketData`. Every subsequent pushed update is masked with that snapshot. The
stored `Authentication` is stale in exactly the same way — the whole structure is a permissions
snapshot, and it must be re-resolved as a unit.

Consequences: a permission revocation does not take effect until reconnect, and a long-lived
connection becomes an unlogged data firehose operating under obsolete authorization.

Recomputing permissions per push is too expensive — every subscriber would hit the database on every
change. The fix treats permissions as **a cache with a deadline** rather than a fact settled at
connect.

**Implemented.** `ModelRestUpdatesWebSocketData` now carries `clientCondition` (what the client asked
for, before permissions narrow it) and `permissionsCheckedAt`. Before any push,
`permissionsStillValid()` runs two checks, cheapest first:

1. **The credential's own lifetime.** Authorization must never outlive the token that established
   it. `Authentication.expiration` is already on the stored auth, so this is a timestamp comparison
   and runs on every push. Expired closes the socket with `VIOLATED_POLICY`.
2. **Re-derivation of permissions**, at most once per `permissionRevalidation` (constructor
   parameter, default 5 minutes). Re-resolving auth also surfaces a terminated session — resolution
   fails outright, and the socket is closed rather than kept alive under a dead credential.

`clientCondition` has to be stored because revalidation must recompute *both* halves. Refreshing only
the mask would leave `condition` — which rows the subscriber can see — derived from the old
permissions, so a narrowed read condition would go unenforced.

The client-message path already re-resolved auth to recompute its condition, but left the mask
snapshotted; it now refreshes both, so the two can never come from different moments.

**Not implemented: the generation counter.** The plan also called for a per-principal counter bumped
on permission-affecting changes and broadcast over the websocket topic pub/sub, giving
millisecond-latency invalidation instead of the bounded window above. It is deliberately deferred:
the topic would have to be global, while topics are registered per-`ServerBuilder`, and — more
importantly — *what counts as a permission-affecting change is domain knowledge the framework does
not have*, the same problem as the audit subject key in 11.2. The mechanism above is correct without
it; the counter only shortens the window. Revisit once there is a concrete deployment whose
revocation latency requirement the interval cannot meet.

---

## 9. Implementation order

Sequenced so each step is independently shippable and testable, and so prerequisites land first.

1. ~~**Request identity**~~ ([section 3](#3-layer-0-request-identity)) — **DONE.** Identity on the
   `Request` base class, `requestIdentity` resolution with the trust rule, `requestIdHeader` on the
   Ktor/Netty/JDK settings, API Gateway's own IDs on AWS, and `subRequest`/`subConnection` for
   derived requests. Covered by `core/src/test/.../http/RequestIdentityTest.kt`.
2. ~~**Multiplex dispatch fix**~~ ([section 4.1](#41-metabulk-bypasses-the-entire-interceptor-chain))
   — **DONE.** Correctness bug in security controls beyond logging. Shipped as a split of the
   interceptor types rather than the rejected `InterceptorScope` enum.
3. ~~**WebSocket permission staleness**~~ ([section 8](#8-websocket-permission-staleness)) —
   **DONE.**
4. ~~**Access log completeness**~~ ([sections 4.2–4.3](#42-websockets-are-not-access-logged-at-all))
   — **DONE.** WebSocket coverage, post-hoc emission with outcome.
5. ~~**Fix the bitwise conditions in `service-abstractions`**~~ — **DONE, twice.**
   ([section 5.3.2](#532-blocking-prerequisite-the-bitwise-conditions-were-broken-in-every-engine))
   — blocks the disclosure log's queryability, and is a live bug worth fixing on its own merits.
   Must ship with per-engine conformance tests, not in-memory ones.
6. ~~**Disclosure log**~~ ([section 5](#5-layer-2-the-disclosure-log-audited)) — **DONE.** Typed-output
   interception, the marker, the record shape and bit registry, the recording `Encoder`, the
   [`RequestRecord`](#58-the-request-record--what-requestid-points-at) table with its two-write
   lifecycle, the fail-closed database sink, and the `_id`-in-partials rule from 5.3.3. Included as
   the `DisclosureAudit` module.
7. ~~**Data access log: conditions and sorts**~~ ([section 6.1](#61-extend-it-to-reads-every-condition-and-every-sort))
   — **DONE**, to the record shape and installation in 6.2. Two limitations recorded there: an
   audited model that no endpoint's serializer reaches has no id and its reads fail rather than going
   unrecorded, and fail-closed here covers privileged internal reads so its blast radius exceeds the
   disclosure log's. Originally described as an extension of existing write auditing, which
   **did not exist**: Section 6 opens by asserting "write auditing already
   exists at `ModelPermissionsTable`". Verified 2026-09: it does not. That class enforces permissions
   and records nothing; `simpleSignals.kt` provides generic `postCreate`/`postChange`/`postDelete`
   decorators, but nothing in either repository writes an audit record from the database layer. So
   this step is a build of the recording layer for reads *and* writes, not an extension of an
   existing one, and no record shape is specified anywhere yet.

   The `requestId`-reachability blocker below is, however, largely resolved: `ModelInfo.table()` is
   already `context(ServerRuntime)`, and since the execution-context refactor a `ServerRuntime`
   carries `initiator.executionId`. The remaining question is placement — `ModelPermissionsTable`
   lives in service-abstractions and cannot see Lightning Server types, so the recording decorator
   belongs on this side.
   — moved ahead of the total-log, because it is what closes the aggregation and oracle channels
   rather than a later refinement. Extends the existing write auditing at the database layer to
   record every condition and sort applied to an audited model.
8. ~~**Total-log: hash chaining and anchoring**~~ ([section 5.7](#57-integrity-out-of-scope-here))
   — **removed from scope.** The emergency total-log is a separate system outside Lightning Server,
   reached for only if these logs prove insufficient or bypassable. Nothing here implements it.
9. **Auth event log** ([section 7](#7-layer-4-the-authentication-event-log)) — the largest greenfield
   piece, since nothing exists to extend. **Groundwork done; the event log itself is not.** Both
   cheap unblockers have shipped: `sessionInfo` now takes a `signals` parameter
   ([7.2](#72-no-usable-extension-point-exists)), and the debug `println` failure strings are now the
   `AuthFailureReason` enum behind a single seam ([7.3](#73-design)). All three adverse findings in
   [7.1](#71-survey-findings) are fixed: session rows are no longer deletable (termination remains),
   a redeemed backup code is retained and marked rather than deleted, and the fabricated `"test"` IP
   and empty user agent are no longer recorded. What remains is the event record type, its sinks,
   and emission at each of the events listed in [7.3](#73-design).

   Correction found while doing that work: 7.2 says the table-decoration approach is unavailable for
   `sessionInfo`. That is true of `signals` specifically, but `ModelInfo.registerChangeListener` is
   public, already applied to the session table, and already sufficient to observe creates, updates
   and deletes. The `signals` seam is still the wider one — it also sees reads.

## 10. Out of scope: external capture layer

Not covered here, but it constrains the above. The requirement is a capture that is **provably
outside application control**, so that the operator cannot be accused of tampering. That forces it
out of process — into the reverse proxy — and the code-side implications are:

- Envoy's `tap` filter is the correct primitive (nginx `mirror` re-issues requests to a second
  upstream, duplicating side effects and capturing no responses).
- **The HTTP tap cannot see websocket frames.** After upgrade the connection is a TCP tunnel; only
  the handshake is captured. A transport-level tap would capture the frames as an opaque byte stream
  requiring custom parsing of both WS framing and the multiplex protocol.

  **Resolved: build neither.** High-sensitivity deployments today already do not stream audited
  models, and the goal is to lift that restriction rather than entrench it. The
  emergency total-log, if it is ever needed, achieves that without any WS parsing: WS disclosures are
  recorded by layer 2, and making them tamper-evident is that separate system's job rather than this
  one's. The residual gap versus direct capture is fabrication-at-write-time.
- If the proxy stamps `x-request-id` and forwards the same value, the external capture and the
  in-process logs join for free (see [3.3](#33-aligning-with-the-proxy-and-with-telemetry)).

## 11. Resolved decisions and remaining questions

### 11.1 Target regime: US-first (resolved)

Build for US requirements first. The system is **optional** — it is inert unless a deployment
registers audited models — so regime-specific behaviour is controlled from configuration rather than
baked into the framework. Non-US regimes are accommodated by the erasure hook in 10.2 rather than by
a second implementation.

### 11.2 Erasure: an opt-in, domain-supplied subject key (resolved)

An immutable audit log and a right-to-erasure request are in direct conflict, and the resolution
(crypto-shredding: encrypt each subject's records under a per-subject key, then destroy the key)
requires knowing **which subject a record belongs to**. The framework cannot determine that — it is
domain knowledge, and any attempt to infer it would be guesswork.

So the framework provides the mechanism and the application supplies the policy:

```kotlin
/** Derives the erasure subject for an audited model. Null means this record is not subject-scoped. */
public fun interface AuditSubjectKey<T> {
    public fun subject(model: T): String?
}
```

Registered alongside the audited model, and **absent by default**. With no key registered there is no
per-subject wrapping and no crypto-shredding — which is the correct default for US deployments, where
the requirement does not apply. A deployment that needs erasure registers a key per audited model and
accepts the added key management.

This must be decided per model *before* its sinks receive their first record, because it determines
how records are encrypted at rest. It cannot be retrofitted to existing records — that is the whole
point of crypto-shredding.

> **The interface exists; crypto-shredding does not.** `AuditSubjectKey` is now declared, and
> `DisclosureAudit` takes a `subjectKeys` map plus a `requireSubjectKeys` flag whose pre-deploy check
> fails the deploy when an audited model has no subject. That exists precisely because the decision
> cannot be retrofitted: it turns "you will discover in a year that these records can never be
> erased" into "this deploy fails until you decide".
>
> **No encryption is performed.** Supplying a key wraps nothing today; records are written in the
> clear and a shred operation does not exist. So a deployment that will need erasure should turn
> `requireSubjectKeys` on from its first deploy — that costs nothing and preserves the option — but
> must not assume erasure works until the wrapping and shredding are built. A US-only deployment needs
> none of this, which is why the flag is off by default.

### 11.3 Auth events — resolved, see section 7

The working assumption was that `SessionManager` already handled much of this and the work would be
an extension of it. **The survey disproved that.** `sessions/src/main` contains no logger calls, no
event records, and no lifecycle hooks; what looks like an audit trail is mutable last-write-wins
state on session and secret rows. Failed logins, token issuance, successful masquerade, and PIN
proofs leave no trace at all.

It is therefore a full fourth layer, designed in [section 7](#7-layer-4-the-authentication-event-log),
and the one with the least to build on. Section 7 also records three findings that are adverse to
auditing regardless of this project — hard-deletable session rows, self-destroying backup-code
evidence, and a fabricated `"test"` IP placeholder.

### 11.4 Auditing reads of the audit log — no special mechanism needed (resolved)

Earlier drafts called for a separate credential path. That was overcomplicated. Reading the audit log
is an ordinary endpoint, and its records land in the same stream as everything else. Self-reference is
not a problem: the log only ever appends, so recording a read of the log simply produces one more
record.

**Weakened since this was resolved, twice over.** The original argument rested on a hash chain making
it impossible for a reader to erase the evidence of their own read; that chain is now
[out of scope](#57-integrity-out-of-scope-here), so what remains is an append-only *convention*, not an
enforced property — anyone with write access to the audit tables can delete their own row. And
[6.2](#62-record-shape-and-installation-resolved) now stores serialized query conditions, so the audit
log holds the sensitive values it audits and is worth protecting more carefully than "an ordinary
endpoint" implies. The resolution still stands for framework *design* — no special credential path is
needed — but the deployment-side controls it assumes are doing more work than this section credits.

Separation of duties between reading the log and administering it remains worth having, but it is an
IAM/deployment concern, not a framework design concern.

### 11.5 Bitfield width — two `Int`s (resolved)

64 bits across two `Int` columns. See
[5.3.1](#531-why-int-columns-and-not-long-ulong-or-bytearray) for the reasoning — `Int` is the only
bitwise-queryable type in the framework — and
[5.3.2](#532-blocking-prerequisite-the-bitwise-conditions-were-broken-in-every-engine) for the two
rounds of engine bugs that had to be fixed first.

Went 96 → 128 → 64 during implementation. It was widened when every field was itemised automatically,
because nested paths ([5.4.1](#541-nested-fields)) grow faster than property counts and dead indices
accumulate forever. Making itemising opt-in
([5.1.1](#511-itemising-is-opt-in-disclosure-is-not)) removed the pressure entirely: a model reserves
bits for the few fields that matter to an audit, so two columns is ample and saves eight bytes on
every row.
