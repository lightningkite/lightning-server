# Service Abstractions — Hardening Handoff Spec

**For:** the agent working in `~/Projects/service-abstractions` (branch `version-2`).
**From:** the Lightning Server hardening pass. Companion to `lightning-server/HARDENING_AUDIT.md`.
**Date:** 2026-06-03

This document specifies the service-abstractions–side changes that the Lightning Server hardening effort depends on or recommends. It is self-contained: every item has file:line anchors (verified against `version-2`), a design, backward-compat analysis, test strategy, and risks. Line numbers are approximate — confirm before editing.

> **Repo state note:** `version-2` currently has unrelated in-progress changes (modified `ai/`, `database-*` files). Keep these changes on separate commits/branch from that work.

> **Consumption model (critical):** service-abstractions is consumed by Lightning Server as a **published Maven artifact** (version ref `serviceAbstractions` in LS `gradle/libs.versions.toml`), *not* a composite `includeBuild`. So anything Lightning Server must consume (notably `Service.verify()` and `SharedResources.close()`) has to be **published (or `publishToMavenLocal` + version bump)** before LS can reference it. Sequence accordingly.

---

## 0. Cross-repo contract (what Lightning Server needs from you)

These are the API surfaces LS is building against. Please keep signatures stable or tell us if they must change.

1. **`Service.verify(): HealthStatus`** — a new, read-only, side-effect-free credential/connectivity probe, distinct from `healthCheck()` (which may have side effects). Default-delegates to `healthCheck()` for compat. LS's new `preflight`/`check` command calls `verify()` across all services; LS's new `/meta/ready` endpoint also calls `verify()` (not `healthCheck()`). **This gates the LS deploy-confidence work — please prioritize it.** (Item §1 below.)

2. **`SharedResources.close()`** — LS engines' new graceful-shutdown path wants to release pooled HTTP clients/executors on SIGTERM. Today `SharedResources` has no `close()`. LS will call it last in its shutdown sequence. (Item §8 below.)

3. **`FileObject`/`PublicFileSystem` suspend changes (item §6, 2.8)** are a **breaking** interface change that LS must migrate to in lockstep. This needs coordinated sequencing and possibly a deprecation bridge — see §6 and the decisions list. Do not ship this silently.

---

## 1. `Service.verify()` split  *(gates LS deploy-confidence; do first)*

**File:** `basis/src/commonMain/kotlin/com/lightningkite/services/Service.kt` (`connect()` ~:80, `healthCheck()` ~:145).

**Design.** Add to the `Service` interface:
```kotlin
/** Read-only, side-effect-free credential + connectivity probe. Safe to run repeatedly and as a
 *  pre-traffic gate. Unlike [healthCheck], must NOT mutate (no temp writes, no sent messages). */
public suspend fun verify(): HealthStatus = healthCheck()
```
Default delegation = every existing impl compiles unchanged. Services whose `healthCheck()` has side effects MUST override `verify()` with a read-only probe:

| Service | File | Read-only probe |
|---|---|---|
| S3 | `files-s3/.../S3PublicFileSystem.kt` | `HeadBucket` (no object write) |
| SES email | SES impl under `email-*` | `GetSendQuota` + `GetIdentityVerificationAttributes` (no send) |
| Generic/SMTP Email | `email/.../EmailService.kt:171` (the unsafe default **sends mail**) | SMTP `connect`+`QUIT` (auth handshake, no message); console/test return OK |
| FCM | `notifications-fcm/.../FcmNotificationClient.kt:385` (decorative) | dry-run send `validateOnly=true` (see §7 / 4.4) |
| Twilio SMS | `sms-twilio/.../TwilioSMS.kt` | account `GET` (likely already what healthCheck does → leave defaulting) |
| Mongo | `database-mongodb/.../MongoDatabase.kt` | `ping` admin cmd (no upsert) |
| SQL/Postgres | `database-sql/.../SqlDatabase.kt`, `database-postgres/.../PostgresDatabase.kt` | `SELECT 1` |
| Cassandra | `database-cassandra/...` | `SELECT now()` — already safe → leave defaulting |
| Redis cache/pubsub | `cache-redis/...`, `pubsub-redis/...` | `PING` |
| DynamoDB cache | `cache-dynamodb/.../DynamoDbCache.kt` | `DescribeTable` (read-only) |
| ClamAV | `files-clamav/...` | reachability ping — already safe → leave defaulting |

Services already read-only (Cassandra, Twilio, ClamAV): **do not override** — default delegation is correct, avoids API churn.

**Backward-compat.** Interface method *with default body* → source/binary compatible for Kotlin implementers. **Caveat:** Kotlin compiles interface defaults to `DefaultImpls`; pure-**Java** implementers of `Service` would not inherit it. Confirm `basis/build.gradle.kts`'s `-Xjvm-default` mode and match it to the existing `connect()`/`disconnect()` defaults; assume Kotlin-only impls (call out in release notes). `explicitApi()` → KDoc required.

**Tests.** Per-module: assert `verify()` produces NO side effect that `healthCheck()` does (e.g. `TestEmailService` has 0 captured emails after `verify()`, 1 after `healthCheck()`). A `basis` test asserting default delegation. Integration (tagged): `verify()` OK on live resource, ERROR on bad credential, and **no temp object created** for S3.

**Risks.** Each override must map provider exceptions → `HealthStatus.ERROR`, not throw. FCM `validateOnly` still needs a syntactically valid token; treat "invalid token" as OK (creds worked), "auth error" as ERROR — that distinction is the whole point of fixing the blind spot.

---

## 2. SQL/Postgres connection pool (HIGH) — item 2.2

**Files:** `database-sql/.../SqlDatabase.kt:62,70,83,98`; `database-postgres/.../PostgresDatabase.kt:98-111` (KDoc lines 19-20,51 falsely claim pooling). No HikariCP dep exists.

**Problem.** `Database.connect(url, driver, user, password)` opens a **fresh physical connection per transaction** (DriverManager-backed), no pool. Under load → connection exhaustion + per-call TCP/TLS/auth overhead.

**Design.**
- `Database.Settings` is a `@JvmInline value class` wrapping `url: String` (`Database.kt:79`). **Do NOT change the Settings type** (ABI break). Carry pool config in the **URL query string** — same pattern S3 already uses for `signedUrlDuration` (`S3PublicFileSystem.kt:312-347`): `maxPoolSize`, `minIdle`, `connectionTimeout`, `idleTimeout`, `maxLifetime`, `validationTimeout`, `poolName`. Tighten the MySQL/Postgres URL regexes to split a trailing `?...` and route pool-only params to Hikari (still forward JDBC-specific params to the JDBC URL).
- Add `hikariCP` to `gradle/libs.versions.toml`; `api(libs.hikariCP)` in both modules. In each `makeDb`: build `HikariConfig` → `HikariDataSource(config)` → `Database.connect(dataSource)`.
- **Lifecycle:** capture the `HikariDataSource` so `disconnect()` (`SqlDatabase.kt:47-50`, `PostgresDatabase.kt:67-72`) closes the pool and resets the lazy so reconnect builds a fresh pool (matches the serverless reconnect contract). `makeDb` is a private ctor param — yield the `DataSource` alongside the `Database` internally; no public API change.
- **H2-mem / SQLite:** default `maxPoolSize=1` (or bypass pooling) — a pooled `mem:` DB sees "table not found" across connections. Important.
- Optional additive: a public secondary constructor/factory accepting a pre-built `HikariDataSource`; add optional pool params (with defaults) to `Database.Settings.Companion.postgres(...)`.

**Backward-compat.** Existing param-less URLs keep working (all defaults). Fix the false "pooling via Exposed" KDoc.

**Tests.** `database-test` BaselineTests/OperationsTests stay green (incl. H2/SQLite); add: N concurrent txns > `maxPoolSize` serialize (not exhaust); `disconnect()` closes pool (Hikari `isClosed`/active→0); URL parse test (pool params extracted, JDBC params still forwarded); assert mem stays single-connection by default.

**Risks.** H2-mem/SQLite breakage under naive pooling (mitigated by size-1 default); over-aggressive Hikari timeouts flapping health.

---

## 3. AWS CRT client timeouts & concurrency (MEDIUM) — item 2.7

**File:** `aws-client/.../AwsConnections.kt:83-86` (CRT clients built with no timeouts/concurrency); `:117-123` (override config only built when OTel present, carries no timeouts); `:93-94` (`used` never incremented → `health` always OK).

**Design.**
- `AwsConnections` is a `SharedResources.Key` singleton (`setup(context)`), no URL. Add **defaulted constructor params** (`connectionTimeout=10s`, `connectionMaxIdleTime=60s`, `maxConcurrency=50`, `apiCallTimeout=30s`, `apiCallAttemptTimeout=10s`) — Lambda-safe defaults. Optionally resolve overrides from a `@Serializable AwsClientSettings` via a `SharedResources.Key`/context lookup when present.
- Apply on `AwsCrtHttpClient.builder()` and `AwsCrtAsyncHttpClient.builder()`: `connectionTimeout`, `connectionMaxIdleTime`, `maxConcurrency`. **Always** build a `ClientOverrideConfiguration` carrying `apiCallTimeout`+`apiCallAttemptTimeout` (add telemetry interceptor when OTel present). Make `clientOverrideConfiguration` **non-null** (it now always has timeouts) — source-compatible for the `?.let` callers in `S3PublicFileSystem.kt:143,160`, `DynamoDbPubSub.kt:153`, `DynamoDbCache.kt:152`; update them to drop the null check. Update `AwsConnectionsTest.kt:238` (`clientOverrideConfiguration is null without OpenTelemetry`) → assert non-null with timeouts.
- **`used`/health (overlaps 4.4):** stop pretending. Set `total = maxConcurrency`; either remove the fake `used` gauge / return OK with a doc note, **or** (fast-follow with metrics §7) track *in-flight requests* via the SDK execution interceptor and expose that as a gauge — documented as in-flight, not socket-pool occupancy (CRT doesn't expose the latter). Coordinate with §7 so 2.7 and 4.4 don't both edit `health` conflictingly: **2.7 sets `total`+timeouts; §7/4.4 owns making `used` real.**

**Tests.** Update the override-config-null test. Assert defaults present + override config carries `apiCallTimeout`. Integration: an unroutable endpoint fails within `apiCallTimeout` instead of hanging.

**Risks.** Defaults too low → spurious timeouts on slow large-object S3 ops (consider per-service: generous/unset `apiCallTimeout` for S3 data ops, tight `connectionTimeout`/`apiCallAttemptTimeout`). `maxConcurrency` tuning.

---

## 4. DynamoDB cache `add` lost-update (MEDIUM) — item 2.9

**File:** `cache-dynamodb/.../DynamoDbCache.kt:286-316`.

**Problem.** Atomic `updateItem` (`SET #exp=:exp, #v = if_not_exists(#v,:z)+:v` with condition not-exists/null/not-expired). When the item exists but is **expired**, the condition fails → fallback `set(key, value, ...)` (`:313`) which is a **blind `putItem`** that overwrites a concurrent writer's value (lost update). Expected: expired ⇒ behaves absent ⇒ counter restarts at `value`, **atomically** (per `CacheTest.expirationTest:198-201`).

**Design.** Replace the blind-`set` fallback with a bounded retry of conditional atomic updates:
1. **Attempt A (live increment):** existing update. On success return `ALL_NEW.value`.
2. **On `ConditionalCheckFailedException` → Attempt B (expired reset):** conditional update with condition `attribute_exists(#exp) AND attribute_type(#exp,N) AND #exp <= :now`, expression `SET #v = :v, #exp = :exp` (overwrite, not increment). Return `ALL_NEW.value`.
3. **On B failing** (someone re-created it live) → loop back to A, small cap (3–5). Exhaustion ≈ impossible (each failure means another writer succeeded).
Guard B with `attribute_type(#exp, N)` so a null-TTL item never matches the expired branch.

**Tests.** Keep `cache-test` `expirationTest:180-211` green. Add a **concurrency test in `cache-test`** (runs all CAS backends): many concurrent `add(key,1,ttl)` around the expiry boundary → final value == count of successful adds (no lost increments). DynamoDB-local test forcing the expired branch (server-side TTL deletion is delayed up to 48h, so the logically-expired row persists — exactly the branch).

**Risks.** Extra round-trips under contention (bounded) — negligible vs. correctness.

---

## 5. `report` correctness + Sentry; remove CloudWatch stub — item 2.1

**Files:** `basis/.../SettingContext.kt:161` (`report(action: suspend () -> Unit) = action()`); `settings.gradle.kts` includes empty `exceptions-sentry/` and `metrics-cloudwatch/` (both contain **no files at all**); `MongoTable.kt:509,533,544` call `report { ... Exception("...") }` — the constructed exception is the lambda's trailing expression and is **silently discarded** (Unit-coerced). So index-creation/search-index failures vanish.

**Recommendation (headline decision): implement `exceptions-sentry`; REMOVE `metrics-cloudwatch` from the build.**
- CloudWatch metrics is redundant: `OpenTelemetrySettings.kt:250-258` already builds an `SdkMeterProvider`+`PeriodicMetricReader` and every scheme sets it, so OTLP export exists; AWS terminates OTLP via ADOT→CloudWatch. A bespoke PutMetricData module duplicates that, costs more, competes with §7. Delete `include(":metrics-cloudwatch")` + the empty dir. If EMF→CloudWatch is ever needed, add a `cloudwatch://` OTel exporter scheme in `OpenTelemetrySettings`, not a module.
- Sentry is NOT redundant: `report` has no shipped backend, so failures genuinely vanish. (LS has `server-sentry`/`server-sentry9` as the integration seam — implies Sentry is wanted.)

**Design — `report` (recommend Option B):**
- Add `suspend fun reportException(throwable: Throwable, context: Map<String,String> = emptyMap())` to `SettingContext` with a default body that logs ERROR via slf4j with `errorFingerprint()` (`ErrorFingerprint.jvm.kt:15`), so failures are visible even with no backend.
- Keep `report(action)` as a wrapper: run the action; on throw, `reportException(it)` then rethrow.
- Fix the three `MongoTable` sites to call `reportException(Exception(...))`.
- Defaulted member addition → source/binary compatible (`TestSettingContext.kt:61` inherits). `explicitApi()` → KDoc.

**Design — `exceptions-sentry` module.** New JVM module mirroring `aws-client/build.gradle.kts` (kotlin.jvm, dokka, signing, vanniktech publish; `api(project(":basis"))`; `lkLibrary(...)`). Add `sentry` version + lib to the catalog. Because `report` lives on `SettingContext` (not a `Service`), expose a **decorator**: `fun SettingContext.withSentry(dsn: String): SettingContext` returning a delegating context whose `reportException` calls `Sentry.captureException(...)`. Store the Sentry client in a `SharedResources.Key` (guard against double `Sentry.init` — it's process-global static). Attach the active OTel trace id as a Sentry tag (prefer the `sentry-opentelemetry` integration; else set `trace` context from `Span.current().spanContext`). LS attaches `withSentry(dsn)` at composition when a DSN setting is present.

**Tests.** `basis`: default `reportException` logs; `report(action)` rethrows after reporting. `exceptions-sentry`: Sentry in-memory/test transport asserts capture + trace-id tag (no network). `database-mongodb`: spy `SettingContext` asserts the index-timeout path now calls `reportException`.

**Risks.** Sentry transitive deps (keep `api`, opt-in). Global init (guard via SharedResources).

---

## 6. `runBlocking` in serialization paths (MEDIUM severity, HIGHEST risk) — item 2.8

**Files:** `files/src/jvmMain/.../ExternalServerFileSerialization.kt:165,181` (`runBlocking { copyAndScan/scan+uploadFile }` inside `deserialize`); `files-s3/.../S3FileObject.kt:548-553` (`assertSignatureValid` does `runBlocking { client.get(...) }` — network round-trip, bounded by the shared client's 60s timeout) reached via `S3PublicFileSystem.parseExternalUrl:186-189`. `FileObject.signedUrl` is a non-suspend `val` (`FileObject.kt:200`); for S3 it's pure-CPU HMAC (no runBlocking) — so the **serialize** side is fine today; the hazard is the **deserialize** side doing real I/O under `runBlocking` on (possibly) a Netty event-loop thread.

**Why hard:** `KSerializer.serialize/deserialize` is non-suspend by contract, and `ExternalServerFileSerializer` is the contextual serializer for `ServerFile` (`files/.../helpers.kt:20`), invoked deep in the HTTP pipeline for any model with a `ServerFile` field.

**Design (two layers):**
- **Layer 1 — make the primitives suspend (breaking interface change):** `FileObject.signedUrl: String` → `suspend fun signedUrl()`; `uploadUrl(timeout)` → suspend; `S3FileObject.assertSignatureValid` → `suspend` (drop internal `runBlocking`, `await` directly); `PublicFileSystem.parseExternalUrl` → `suspend`. Blast radius: `S3FileObject`, `KotlinxIoPublicFileSystem` (`signedUrl:199-200`), `files-test` fakes, and call sites (`demo` FileExamples — already in suspend handlers; `files-test/FileSystemTest.kt`). This **breaks** two published interfaces (`FileObject`, `PublicFileSystem`) — `apiCheck` will flag it. Decide: clean break (recommended) vs a temporary `@Deprecated` blocking bridge (re-introduces the hazard — discouraged).
- **Layer 2 — get I/O out of the serializer:** even with suspend primitives, `deserialize` can't call them. Restructure so `deserialize` only parses/validates the URL and records a *pending action*; move the actual scan/copy/upload + signature-network-fallback into a **suspend prepare/finalize pass** in the request coroutine — this already matches the existing two-phase `future:`/`future-prescanned:` upload protocol. **This needs a coordination hook in Lightning Server** (where the prepare pass runs in the request pipeline). For the serialize side, keep an internal CPU-only S3 signer the serializer can call synchronously (signed-URL generation is pure CPU; network only ever happens on the verify/deserialize side), so we don't force a prepare-pass on every response.

**Security invariant:** the prepare pass must run **before** the `ServerFile` is trusted/persisted — preserve "scan-before-use". Getting this wrong creates a virus-scan bypass. Highest QA priority of all SA items.

**Tests.** Update `files-test/FileSystemTest.kt` to suspend calls; assert `assertSignatureValid`'s foreign-signature path runs the network fallback without `runBlocking` (cancellable / on a test dispatcher). Serializer test: round-trip a `ServerFile` asserting NO `runBlocking` on the stack during (de)serialize. LS integration: a model with a `ServerFile` field deserializes on a Netty worker without deadlock.

**Risks.** Largest API break of the set; needs coordinated SA→LS release sequencing; scan-bypass risk if the prepare-pass ordering is wrong.

---

## 7. Metrics layer + real health checks — items 4.1, 4.4

**Established facts:** `OpenTelemetrySub` already *is* a `Meter` (`otel-jvm/.../kotlinify.kt:94-98`) and the SDK `MeterProvider` is wired for every export scheme — only *instrument recording* is missing. Reference-correct manual-span pattern at `database-sql/.../SqlCollection.kt:47-77` (`tracer.spanBuilder(...).startSpan()` + `withContext(span.asContextElement())`).

**4.1 metrics.** Add `metrics.kt` to `otel-jvm` exposing instrument factories on `OpenTelemetrySub`:
- `redMetrics(system): RedMetrics` → `opsCounter` (`LongCounter`, attrs `{system, operation, outcome=ok|error}`), `latencyHistogram` (`DoubleHistogram`, unit `s`, OTel semconv name `db.client.operation.duration`), and a `record(operation, attrs){ block }` suspend-inline that times + counts + records latency in one call (compose with the existing `span`).
- Cache `get` additionally tags `cache.hit=true|false` (derive ratio in backend).
- `poolGauge(name, attrs, callback)` → async `ObservableLongGauge` (`gaugeBuilder(...).buildWithCallback`), correct for pool utilization (sampled at export).
- Create instruments **once per service instance** (`private val`), no-op holder when `openTelemetry == null` (mirror how `span` short-circuits). Wire Mongo/Redis/S3/PubSub/DynamoDB op methods.
- **Cardinality:** never tag with keys/ids; reuse `TelemetrySanitization.hashCacheKey`. Names follow OTel semconv for portable dashboards.

**4.4 health + a latent bug:**
- **FCM** (`FcmNotificationClient.kt:385`, always OK): add `protected open fun sendDryRun(message) = messaging.send(message, /*dryRun=*/true)` (test-overridable, mirrors `sendMulticast:158`); `healthCheck()`/`verify()` build a minimal `Message` and dry-run it. Map: success / `INVALID_ARGUMENT`(dummy token) → OK (creds work); `UNAUTHENTICATED`/`PERMISSION_DENIED` → ERROR; transport → WARNING/ERROR. On `Dispatchers.IO`, timeout-wrapped, span-wrapped. Respect `healthCheckFrequency`.
- **AWS `used`** (`AwsConnections.kt:93-94`): remove the fake signal now (return OK with doc note, set `total=maxConcurrency` per §3); add a real *in-flight-request* gauge via the existing execution interceptor (`:119`) as a fast-follow once §3's `maxConcurrency` lands — documented as in-flight, not socket-pool.
- **Mongo pool-listener inversion (bonus bug):** `MongoDatabase.kt:192-200` — `connectionCheckedIn` *increments* `active` and `connectionCheckedOut` *decrements* it, so `active` tracks **idle**, not in-use, and `poolHealth` (`:240-257`) is backwards. Swap them (increment on checkedOut, decrement on checkedIn). Unit-test with synthetic events.

**Tests.** `otel-jvm` with `InMemoryMetricReader` asserts counters/histograms/gauges + attrs + no-op-when-null. FCM dry-run mapping (override `sendDryRun`, throw each error code). Mongo listener utilization test. AWS health no longer reports a fabricated percentage.

---

## 8. Mongo/Lettuce span parenting (HIGH, CONFIRMED) — item 4.5

**Confirmed root cause.** `MongoDatabase.kt:205-220` builds `MongoTelemetry` + `addCommandListener(telemetry.newCommandListener())` with **no `ContextProvider`**. The command-listener parents each span to `Context.current()` on the driver's **async I/O thread**, where the OTel context is root/empty — the caller's request-span Context (in the coroutine context) never reaches that thread. Result: orphaned/mis-parented Mongo spans. Lettuce/Redis (`RedisCache.kt:129-141`, `LettuceTelemetry.newTracing()`) shares the risk. SQL & in-memory are correct (manual span + `asContextElement`).

**Design (do B now, A as enhancement):**
- **B (robust, no alpha dependency — ship first):** mirror SQL. In `MongoTable`'s op wrappers, wrap each operation in a manual span via the existing `OpenTelemetrySub.span(...)` (`kotlinify.kt:72-79`, which applies `asContextElement` through `use`). Keep the `MongoTelemetry` command listener for command-level attributes. Now there's always a correctly-parented operation span under the request; command spans nest under it when context reaches the driver thread, and are merely best-effort otherwise. Same for Redis (`RedisCache` already wraps ops in `otel.span` — verify every public op does).
- **A (enhancement — verify first):** set a `com.mongodb.reactivestreams.client.ReactiveContextProvider` on `MongoClientSettings` that reads the OTel Context from the Reactor subscriber context (the Kotlin coroutine→reactor bridge carries context elements), to re-parent the command-level spans. **Verify empirically that the pinned `opentelemetry-mongo-3.1` `2.19.0-alpha` honors the driver `RequestContext` for parenting** before relying on it (alpha instrumentation historically only *consumes* a present `RequestContext`). If it doesn't honor it, drop A; B stands alone.

**Tests (definitive regression).** OTel `InMemorySpanExporter`: start a parent span, run a Mongo op inside `withContext(parentSpan.asContextElement())`, assert the op span's `parentSpanId == parentSpan.spanId` (and once A lands, command spans nest under the op span). Same harness for Redis. Copy the assertion style from SQL (already correct).

**Backward-compat.** Internal; no public API change. Span structure改善 (better parenting) may shift dashboards keyed on orphaned names — intended.

---

## Recommended phasing (service-abstractions)

1. **`Service.verify()` (§1)** — unblocks LS deploy-confidence. Publish/bump after. **Do first.**
2. **`report` + Sentry; remove CloudWatch (§5)** + **Mongo span parenting path B (§8)** — high-value, low/no API risk.
3. **DynamoDB `add` (§4)** — isolated, no API change.
4. **AWS timeouts (§3)** — minor API change (override config non-null).
5. **SQL/Postgres HikariCP (§2)** — additive API.
6. **Metrics layer + health checks (§7)** — additive; Mongo listener inversion fix; FCM dry-run; coordinate AWS `used` with §3.
7. **`SharedResources.close()` (§0.2)** — small; LS shutdown wants it.
8. **Span parenting path A (§8)** — after verifying mongo-3.1 honors `RequestContext`.
9. **Serialization suspend (§6, 2.8)** — LAST, most invasive, breaks `FileObject`/`PublicFileSystem`, needs coordinated LS release. Reserve the most review/QA (scan-before-use invariant).

`apiCheck`/`.api` baselines: §3, §6 change public surface — regenerate. §1, §5, §7, §8, §0.2 are additive/internal.

---

## Decisions needed (please resolve or tell us your preference)

1. **§1 verify():** confirm no pure-Java `Service` implementers exist (else need `@JvmDefaultWithCompatibility`). OK with SMTP `verify()` = connect+QUIT (proves reachability without sending) vs returning WARNING?
2. **§5:** agree — implement Sentry, remove `metrics-cloudwatch`? And `report` Option B (add `reportException(Throwable)`, keep `report(action)` wrapper)?
3. **§6 (2.8):** clean break on `FileObject`/`PublicFileSystem` vs a temporary deprecated blocking bridge? Where should the post-deserialize suspend "prepare pass" hook into the LS pipeline (the `future:` two-phase protocol already models this)? Single coordinated minor bump, or does 2.8 get its own major given the interface breaks?
4. **§2:** mem/SQLite — pool at size 1 or bypass pooling entirely (leaning bypass for `mem:`)? Any downstream passing JDBC params in the URL that the tightened regex must still forward?
5. **§3 / §7:** who owns the real AWS utilization gauge (§3 sets `total`+timeouts; §7/4.4 makes `used` real via in-flight tracking)? Per-service timeout differentiation (S3 large GET vs DynamoDB point read)?
6. **§8:** ship manual-span path B first (recommended); gate path A on verifying mongo-3.1 `2.19.0-alpha` honors `RequestContext`.
7. **Target versioning:** one coordinated minor bump for §1–§5,§7,§8, with §6 as a separate major?
