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

**AWS serverless needs no such setting.** API Gateway mints `requestContext.requestId` itself, so it
is authoritative with no configuration, and it matches the ID in the gateway's own access logs. For
WebSockets the adapter uses `requestContext.connectionId`, which is stable for the socket's whole
lifetime — exactly the correlation scope wanted for a connection.

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
public fun interface ConnectionInterceptor : HttpInterceptor

/** Every logical request, sub-requests included. Access log, audit, rate limiting. */
public fun interface LogicalRequestInterceptor : HttpInterceptor
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
| `GzipInterceptor` | `Connection` | compression applies to the physical body; per-sub-request would double-encode |
| `SecurityHeadersInterceptor` | `Connection` | headers belong to the physical response |
| `CorsInterceptor` | `Connection` | plus `WebSocketHandlerInterceptor`, as before |
| `AccessLogInterceptor` | `LogicalRequest` | otherwise one line for `/meta/bulk` regardless of contents |
| `RateLimitInterceptor` | `LogicalRequest` | otherwise a bulk request of 100 sub-requests costs one unit — a bypass |

**Ordering consequence worth knowing:** interceptors nest by kind first and installation order
second, so every `ConnectionInterceptor` wraps every `LogicalRequestInterceptor` regardless of
install order. This only reorders a pair spanning both kinds, and the nesting it produces is the one
you want — but it is a behaviour change for such a pair.

### 4.2 WebSockets are not access-logged at all

`AccessLogInterceptor` implements only `HttpInterceptor`. `WebSocketHandlerInterceptor` exists and
`CorsInterceptor` already implements both, so the pattern is established — the access log simply does
not use it. `MultiplexWebSocketHandler` then hides many logical sockets inside one physical
connection, so even connection-level logging would under-report.

**Fix.** `AccessLogInterceptor` implements both interfaces. Log connect, disconnect (with close code
and reason), and each logical multiplexed socket open/close, all carrying the `connectionId` from
3.4.

### 4.3 The access log records too little, too early

`AccessLogInterceptor.kt:42` logs *before* calling `cont(request)`:

- No status code, duration, or response size. A request that dies mid-handler still reads as a clean
  "accessed."
- The principal is snapshotted before the handler runs, so a masquerade established inside the
  handler is attributed to the wrong actor.

**Fix.** Emit after `cont` returns, in a `finally` so failures are still recorded, with outcome and
duration included, and re-read the resolved principal at that point.

---

## 5. Layer 2: the disclosure log (`@Audited`)

The layer that delivers the actual compliance value.

### 5.1 Marking

```kotlin
/** Records disclosure of this model whenever it leaves through a typed endpoint. */
@Target(AnnotationTarget.CLASS)
public annotation class Audited

/** Records invocation of this endpoint, including its input, as an auditable operation. */
@Target(AnnotationTarget.CLASS)
public annotation class AuditedOperation
```

`@Audited` on a model covers data disclosure. `@AuditedOperation` on an endpoint covers actions that
are not model reads at all — triggering a payment, exporting a report, revoking a session. Both use
the same interception point, so this costs no additional machinery.

The annotation establishes the **floor**. The effective level is resolved at registration and may be
raised by configuration (metadata only → include IDs → include values) but never lowered, so a
deployment can tighten auditing without a recompile and cannot loosen it by accident.

### 5.2 Interception

In `ApiHttpHandler.handle` (the interface's default implementation), after the endpoint's typed
`handle` returns and before serialization.

**Precompute the audit plan per endpoint at registration**, by walking the `outputType` descriptor to
determine whether the output graph contains audited models and at what paths. `@Audited` models must
be found anywhere in the graph — bare, in `List<T>`, in `Partial<T>`, nested inside a wrapper, as map
values. At runtime the plan is a direct walk of the value with no reflection, keeping the hot path
cheap.

Endpoints whose output graph contains no audited models get a null plan and pay nothing.

### 5.3 Record shape — normalisation is the whole game

The naive shape (one row per disclosed record, each carrying IP, principal, timestamp, and bitfield)
is unaffordable: a 10k-row query produces 10k rows, each redundantly repeating request-constant data.

Two reductions, together roughly two orders of magnitude:

1. **Request-constant data lives once.** IP, principal, timestamp, endpoint, and outcome are
   properties of the request, recorded once in the layer-1 record and referenced by `requestId`.
   Disclosure records never repeat them.
2. **Group by bitfield.** Within one query result nearly every row shares the same disclosed-field
   set. So the unit is not a record, it is a *(request, table, field-set)* group with an ID list.

```kotlin
@Serializable
public data class DisclosureRecord(
    val requestId: String,
    val tableId: Int,          // from the table registry
    val fields0: Int,          // bits 0..31   } disclosed-field bitfield;
    val fields1: Int,          // bits 32..63  } see 5.4 for indices,
    val fields2: Int,          // bits 64..95  } 5.4.1 for why three Ints
    val ids: List<String>,     // records disclosed with exactly this field set
)
```

A 10k-row query collapses from 10k records to typically one to three.

#### 5.3.1 Why three `Int`s and not `Long`, `ULong`, or `ByteArray`

**Because `Int` is the only type the framework can query bitwise.** The entire bitwise condition
surface is `Condition<Int>` (`Condition.kt:254-275`):

| Condition | Semantics |
|---|---|
| `IntBitsClear(mask)` | all mask bits clear — `on and mask == 0` |
| `IntBitsSet(mask)` | all mask bits set — `on and mask == mask` |
| `IntBitsAnyClear(mask)` | at least one mask bit clear — `on and mask < mask` |
| `IntBitsAnySet(mask)` | at least one mask bit set — `on and mask > 0` |

There are no `Long`, `ULong`, or `ByteArray` equivalents. A `ULong` or `ByteArray` bitfield would be
**storable but not queryable** — "which requests disclosed the SSN field?" would require a full scan
and client-side filtering, which is unusable at audit-table volume.

Layout: bit index `i` lives in column `i / 32` at bit `i % 32`. "Was field `i` disclosed" is
`IntBitsAnySet(1 shl (i % 32))` on the corresponding column; a query spanning several fields is an
`Or` of per-column conditions. All expressible in the existing condition set.

96 bits is the working ceiling. Extending later means adding a `fields3` column defaulting to `0`,
which is automatically correct for historical records — absent means not disclosed.

#### 5.3.2 Blocking prerequisite: the bitwise conditions are broken in every SQL engine

**This must be fixed before the disclosure log is built, because the whole queryability argument in
5.3.1 rests on it.**

`SqlFieldSet.single(value)` returns `(column, maskLiteral)` (`SqlConditionMapping.kt:48`). Two of the
four mappings use `col.first` (the column) where `col.second` (the mask literal) was intended:

| Condition | Intended | Emitted by SQL + Postgres | |
|---|---|---|---|
| `IntBitsClear` | `col & mask = 0` | `col & mask = 0` | ✓ |
| `IntBitsSet` | `col & mask = mask` | `col & mask = col` | ✗ |
| `IntBitsAnyClear` | `col & mask < mask` | `col & mask < col` | ✗ |
| `IntBitsAnySet` | `col & mask > 0` | `col & mask > 0` | ✓ |

Sites: `SqlConditionMapping.kt:263` and `:271`; `ConditionMapping.kt:265` and `:289` (Postgres
repeats the same error). Failing case: `field = 0b0011`, `mask = 0b0001`. `IntBitsSet` should be
true; the emitted `0b0001 = 0b0011` is false.

MongoDB (`bson.kt:151-154`) has a different bug — all four are transposed All↔Any:

```kotlin
is Condition.IntBitsAnyClear -> into.sub(key)["\$bitsAllClear"] = mask   // should be $bitsAnyClear
is Condition.IntBitsAnySet   -> into.sub(key)["\$bitsAllSet"]   = mask   // should be $bitsAnySet
is Condition.IntBitsClear    -> into.sub(key)["\$bitsAnyClear"] = mask   // should be $bitsAllClear
is Condition.IntBitsSet      -> into.sub(key)["\$bitsAnySet"]   = mask   // should be $bitsAllSet
```

The in-memory `invoke()` implementations in `Condition.kt` are all correct, so any test running
against an in-memory table passes while the real database returns different rows. That is almost
certainly how this survived, and it means the fix must come with **conformance tests that run against
each real engine**, not in-memory ones. Cassandra's `ConditionNormalizer.kt:101-104` negation table is
logically correct and needs no change.

This is a live correctness bug in `service-abstractions` affecting anyone using bitwise conditions
today, independent of audit logging, and should be reported and fixed there on its own merits.

### 5.4 The field registry — append-only, not versioned

A bitfield keyed to declaration order is fragile: inserting or reordering a property shifts every
bit, and all historical records silently change meaning. Storing a schema version per record fixes
correctness but adds a lookup to every read and a version bump to every model change.

**Use an append-only field registry instead.** Each field of each audited model is assigned a
permanent bit index the first time it is seen; indices are never reused and never shift.

```kotlin
@Serializable
public data class AuditFieldRegistration(
    val tableId: Int,
    val fieldName: String,
    val bitIndex: Int,
)
```

Consequences:

- Bit N means the same field forever. Historical records stay readable with no version lookup.
- Adding or reordering fields never invalidates existing records.
- A per-record schema reference becomes unnecessary, saving bytes in the highest-volume table.

Registration happens at startup from the serializer descriptors. A **removed** field keeps its index
permanently reserved — the registry is append-only, so a field is retired rather than deleted, and
records that referenced it remain interpretable.

Startup must fail loudly if a registered field name is absent from the current descriptor *and* has
not been explicitly marked retired, so that a rename is caught rather than silently allocating a new
bit and orphaning history.

### 5.5 Field presence semantics

"Disclosed" means the field held a non-default value in the payload the client received. Defaults
(null, zero, empty string, empty collection) read as not disclosed.

This is deliberately a statement about the *payload*, not about permissions: it is the disclosure
question, and it is measurable at the typed layer without reaching below it. The documented
consequence is that a field whose true value equals its default is indistinguishable from a masked
one. That is acceptable — in both cases the client learned nothing beyond the default.

### 5.6 Failure behaviour: fail-closed

If the audit write fails, the request fails. For audited models the disclosure must not happen unless
it was recorded.

This is consistent with the framework's fail-fast stance, but it has a hard architectural
consequence: **the audit sink is in the availability path**, so it cannot be a network call. It must
be a local append-only file with fsync, with a separate shipper draining it. This is the same
durability design the external capture layer uses.

Configurable, defaulting to fail-closed whenever any `@Audited` model is registered.

### 5.7 Integrity

Records are hash-chained: each carries the hash of its predecessor, with per-instance sequence
numbers. This makes both tampering and **truncation** detectable — truncation being the realistic
attack, since an append-only sink cannot be edited but can be silently stopped.

### 5.8 Anchoring: extending external assurance to channels the proxy cannot see

This is the mechanism that makes streaming audited models over WebSockets acceptable, and it
generalises to any disclosure the external capture layer cannot observe.

The problem: layer 2 is written by the application, so on its own it carries no defence against the
application operator rewriting history. That defence is exactly what the external capture layer
provides — but the proxy cannot see WebSocket frames (see
[section 10](#10-out-of-scope-external-capture-layer)), so WS disclosures get layer-2 assurance only.

The proxy cannot see WS frames, **but it can see HTTP requests.** So:

> Periodically emit the layer-2 hash-chain head as an ordinary HTTP request through the proxy.

The proxy captures that request into the un-tamperable store like any other. The chain head is a
cryptographic commitment to every layer-2 record written before it — including the WebSocket ones the
proxy never saw. Once a head is anchored, the application cannot retroactively alter or delete any
record preceding it without producing a chain that fails to reproduce an already-captured head.

Assurance this does and does not provide:

- **Does** prevent retroactive tampering, deletion, and truncation of WS audit records — the same
  guarantee the external layer gives for HTTP, bounded by the anchor interval.
- **Does not** prevent fabrication at write time: an already-compromised application can write a
  false record and anchor it honestly. Layer 1's direct capture is stronger in that specific respect,
  which is why it remains the backstop for HTTP.

Anchor interval is a tradeoff between request overhead and the size of the retroactively-editable
window; the window is bounded by the interval, so a short interval on a low-volume channel is cheap.
The interval belongs in configuration.

Because the anchor is just an HTTP request, this needs no proxy support beyond what layer 1 already
does, and no WebSocket frame parsing anywhere.

### 5.9 Sinks

The audit stream is a typed event stream with pluggable sinks, not a single log. Auditors need to
*query*; an encrypted object store is unqueryable by design. Expect at minimum a queryable sink
(Postgres or a SIEM) for investigation alongside the tamper-evident sink as system of record.

---

## 6. Layer 3: the data access log

Write auditing already exists at `ModelPermissionsTable`. This layer stays where it is, and its
scope is now explicit: it answers "what did the code touch," including privileged internal reads that
never reach a user.

One architectural advantage worth exploiting: `Modification<T>` is a first-class serializable value
in this stack. Logging `(condition, modification, affected ids)` is far more compact than before/after
images and strictly more informative about intent. Reads at this layer, where enabled, should record
the `Condition<T>` for the same reason.

Operations that are neither model reads nor model writes are covered by `@AuditedOperation`
([section 5.1](#51-marking)) and, as a backstop, by the action log.

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

Auth events are their own record type, sharing the request ID, hash chain (5.7), anchoring (5.8), and
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

The three adverse findings in 7.1 should be fixed alongside: session rows want `delete =
Condition.Never` with soft-termination only, backup-code use wants a soft-disable with a timestamp
rather than a hard delete, and the `"test"` IP placeholder wants to fail rather than fabricate.

## 8. WebSocket permission staleness

Independent of audit, this is a live permissions bug and should be fixed alongside.

`ModelRestUpdatesWebsocket` snapshots the mask at connect time
(`ModelRestUpdatesWebsocket.kt:65`, `mask = info.table(access).mask()`) and stores it in
`ModelRestUpdatesWebsocketData`. Every subsequent pushed update is masked with that snapshot. The
stored `Authentication` is stale in exactly the same way — the whole structure is a permissions
snapshot, and it must be re-resolved as a unit.

Consequences: a permission revocation does not take effect until reconnect, and a long-lived
connection becomes an unlogged data firehose operating under obsolete authorization.

Recomputing permissions per push is too expensive. Two cheap mechanisms, both of which should be
implemented:

**1. Bind connection authorization to credential lifetime.** A socket's authorization must never
outlive the token that established it. The token's `exp` is already known; at expiry, force re-auth
or disconnect. This alone bounds the exposure window at token TTL and needs no new infrastructure.

**2. Permissions generation counter.** A per-principal integer in cache, bumped on anything that
could affect permissions — role change, membership change, deactivation. Rather than reading it per
push, **broadcast the bump over the existing websocket topic pub/sub** and have affected connections
re-resolve reactively. Zero per-push cost, invalidation within milliseconds.

Together: the generation counter handles explicit revocation immediately; token expiry bounds
anything the counter misses.

---

## 9. Implementation order

Sequenced so each step is independently shippable and testable, and so prerequisites land first.

1. ~~**Request identity**~~ ([section 3](#3-layer-0-request-identity)) — **DONE.** Identity on the
   `Request` base class, `requestIdentity` resolution with the trust rule, `requestIdHeader` on the
   Ktor/Netty/JDK settings, API Gateway's own IDs on AWS, and `subRequest`/`subConnection` for
   derived requests. Covered by `core/src/test/.../http/RequestIdentityTest.kt`.
2. **Multiplex dispatch fix** ([section 4.1](#41-metabulk-bypasses-the-entire-interceptor-chain)) —
   correctness bug in security controls beyond logging. Includes `InterceptorScope`.
3. **WebSocket permission staleness** ([section 8](#8-websocket-permission-staleness)) — live
   permissions bug.
4. **Access log completeness** ([sections 4.2–4.3](#42-websockets-are-not-access-logged-at-all)) —
   websocket coverage, post-hoc emission with outcome.
5. **Fix the bitwise conditions in `service-abstractions`**
   ([section 5.3.2](#532-blocking-prerequisite-the-bitwise-conditions-are-broken-in-every-sql-engine))
   — blocks the disclosure log's queryability, and is a live bug worth fixing on its own merits.
   Must ship with per-engine conformance tests, not in-memory ones.
6. **Disclosure log** ([section 5](#5-layer-2-the-disclosure-log-audited)) — the largest piece.
   Field registry and record shape first, then interception, then hash chaining, then sinks.
7. **Anchoring** ([section 5.8](#58-anchoring-extending-external-assurance-to-channels-the-proxy-cannot-see))
   — small once the chain exists, and it is what lets audited models stream over WebSockets.
8. **Data access log alignment** ([section 6](#6-layer-3-the-data-access-log)) — extend existing
   write auditing to record conditions, share the record format.
9. **Auth event log** ([section 7](#7-layer-4-the-authentication-event-log)) — the largest greenfield
   piece, since nothing exists to extend. Start with the two cheap unblocking changes: give
   `sessionInfo` a `signals` parameter ([7.2](#72-no-usable-extension-point-exists)), and turn the
   existing debug `println` failure strings into a failure-reason enum
   ([7.3](#73-design)). The three adverse findings in
   [7.1](#71-survey-findings) — hard-deletable session rows, self-destroying backup-code evidence,
   and the fabricated `"test"` IP — are independently worth fixing and can ship ahead of the rest.

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
  [anchoring mechanism](#58-anchoring-extending-external-assurance-to-channels-the-proxy-cannot-see)
  achieves that without any WS parsing: WS disclosures are recorded by layer 2 and made
  tamper-evident by committing the chain head through the HTTP path the proxy already captures.
  The residual gap versus direct capture is fabrication-at-write-time, documented in 5.8.
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
is just an `@AuditedOperation` endpoint, and its records land in the same stream as everything else.
The hash chain (5.7) and the append-only sink mean a reader cannot erase the evidence of their own
read, which is the property that actually matters. Self-reference is not a problem here: the log only
ever appends, so recording a read of the log simply produces one more record.

Separation of duties between reading the log and administering it remains worth having, but it is an
IAM/deployment concern, not a framework design concern.

### 11.5 Bitfield width — three `Int`s (resolved)

96 bits across three `Int` columns. See [5.3.1](#531-why-three-ints-and-not-long-ulong-or-bytearray)
for the reasoning — `Int` is the only bitwise-queryable type in the framework — and
[5.3.2](#532-blocking-prerequisite-the-bitwise-conditions-are-broken-in-every-sql-engine) for the
engine bugs that must be fixed first.
