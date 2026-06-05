# Lightning Server — Hardening Audit

**Date:** 2026-06-03
**Branch:** `version-5-openid-provider`
**Scope:** Lightning Server + its dependency Service Abstractions (`~/Projects/service-abstractions`)
**Dimensions:** Security · Reliability · Performance · Observability · Downstream deploy-confidence

This document collects hardening recommendations from a multi-agent review pass. Findings are grounded with `file:line` references. Items already resolved on this branch (Apple JWT verification, JWT algorithm-confusion, weekly-frequency math, `RawPath` dead code) are **not** repeated here — see `SECURITY_AUDIT_SESSIONS.md` / `SECURITY_AND_QUALITY_ISSUES.md` for those.

Severity legend: 🔴 Critical · 🟠 High · 🟡 Medium · ⚪ Low/Info

---

## Implementation progress (`version-5`, this pass)

Implemented + verified (working tree, uncommitted):
- **Security:** 0.1, 0.5, 1.5 (OID, on `version-5-openid-provider`); 1.1, 1.2, 1.3, 1.7, 1.8, 1.10 (on `version-5`). 1.9 deferred (TODO in code). Auth changes added a **breaking** required `cache` param to `AuthEndpoints`.
- **Engine reliability:** 2.3, 2.4, 2.5, 2.6, 2.10 — shared `EngineReliabilitySettings` in `engine-local`; all four engine test suites green.
- **Deploy-confidence:** 5.3.7 `api-check` (kschema diff + CLI + allowlist; `demo/api-baseline.json` generated; `apiCheck` runs clean) and 5.3.5 settings JSON-schema export (`settingsSchema` CLI; `additionalProperties:false`). 5.3.1 preflight + 5.3.3 `/meta/ready` remain SA-gated (`verify()`).
- **CI/release (§6):** detekt (report-only + baseline), OWASP dependency-check (advisory/report-only CVE warning, no auto-update), gitleaks, Kover `koverVerify` gate (minBound 0), `check` in CI, workflow permissions/concurrency/timeouts, `publishCentral` restricted to `v*`. Dependabot intentionally NOT used — automated dependency PRs are treated as a supply-chain risk; upgrades stay manual/deliberate.

Resolved status is reflected per-row below. Service-abstractions items (§ in `SERVICE_ABSTRACTIONS_HARDENING.md`) are handed off to that repo's owner. Still open here: 0.2 (user), 1.4/2.11/3.4 won't-fix, 4.2/4.3 observability (trace-id in errors, structured access log), 4.5 (SA), preflight/`/meta/ready` (SA-gated).

---

## 0. Fix-now (small, high-severity)

| # | Item | Location | Severity | Status |
|---|------|----------|----------|--------|
| 0.1 | OAuth client secrets minted with non-crypto RNG (`kotlin.random.Random`, an LCG) | `sessions-openid-provider/.../OauthClientEndpoints.kt:17,69` | 🔴 | ✅ Fixed (`version-5-openid-provider`) |
| 0.2 | Hardcoded AWS access-key ID committed in CI | `.github/workflows/publishInternal.yml:57` | 🟠 | Open |
| 0.3 | ~~`testPR.yml` does not trigger on PRs to `master`~~ | `.github/workflows/testPR.yml:5` | — | ❌ Retracted — based on a stale CLAUDE.md claiming `master` is default. The real default branch is `version-5`, which `branches: version-*` already matches, so PR tests **do** run. |
| 0.4 | OAuth consumer login flow generates `state` but never persists/verifies it → login-CSRF | `sessions-oauth/.../OauthProofEndpoints.kt`, `OauthCallbackEndpoint.kt:33` | 🟠 | Open (lives on `version-5`, not OID-specific) |
| 0.5 | OIDC `/introspect` & `/revoke` effectively unauthenticated for public clients | `sessions-openid-provider/.../OpenIdProviderEndpoints.kt:371-401,472` | 🟠 | ✅ Mitigated (`version-5-openid-provider`) |

**0.1** ✅ Fixed — now uses `CryptographyRandom.nextBytes(24)` + `fastHash()`, matching `SessionManager.newSession`.
**0.2** Move the access-key ID to `secrets.*`; confirm the IAM principal is publish-only/least-privilege; add gitleaks.
**0.3** ❌ Retracted — not an issue; `version-5` (the real default) matches `version-*`.
**0.4** Persist the issued `state` (cache keyed to the browser/session), reject callbacks whose `state` wasn't issued / isn't single-use; validate OIDC `nonce`.
**0.5** ✅ Mitigated — `/token`, `/introspect`, `/revoke` are now rate-limited per client + source IP (`constrainAttemptRate`). Note: public clients *cannot* authenticate by design (no secret); protection rests on token possession (high-entropy) + the existing client-ownership check + rate limiting. Forcing client auth would break legitimate public-client revoke flows, so it was intentionally not added.

---

## 1. Security

| # | Finding | Location | Severity |
|---|---------|----------|----------|
| 1.1 | ✅ **Fixed** (`version-5`) — Hash comparisons documented as constant-time but use short-circuiting `String ==` → now `MessageDigest.isEqual` on raw bytes | `core/.../encryption/SecureHash.kt:114,126` | 🟡 |
| 1.2 | ✅ **Fixed** (`version-5`) — Refresh-token secret logged in plaintext when `debug=true` → now logs session id only | `sessions/.../SessionManager.kt:376` | 🟡 |
| 1.3 | ✅ **Fixed** (`version-5`) — JWT `read()` never validates `iss` → now checks `claims.iss == issuer()` | `sessions/.../token/JwtTokenFormat.kt` | 🟡 |
| 1.4 | ~~JWT `exp`/`nbf` evaluated before signature verification~~ | `sessions/.../token/JwtTokenFormat.kt:85-102` | — | ❌ **Won't-fix (by design).** Payloads are public so pre-verify reads leak nothing; a forged token's exp/nbf are attacker-chosen (no oracle); checking time first just skips the expensive signature check for self-declared-invalid tokens. Signature still required before acceptance. |
| 1.5 | ✅ **Fixed** (`version-5-openid-provider`) — No rate-limiting on any OIDC endpoint; each `/token` runs PBKDF2 (100k iters) per active secret → credential-stuffing / CPU-exhaustion DoS | `sessions-openid-provider/.../OpenIdProviderEndpoints.kt:472`, `OauthClientEndpoints.kt:74` | 🟡 |
| 1.6 | ~~User enumeration via distinct "no user" / "multiple users" login errors~~ | `sessions/.../AuthEndpoints.kt:380-389` | — | ❌ **Not an issue.** `proofsCheck` validates every proof's signature *before* this message, so the caller has already cryptographically proven control of the property+value; the message only echoes values they themselves submitted. No enumeration leak — kept as helpful diagnostics. |
| 1.7 | ✅ **Fixed + tested** (`version-5`) — TOTP code reuse → now single-use per time-step via atomic `claimOnce` (RFC 6238 §5.2) | `sessions/.../TimeBasedOTPProofEndpoints.kt` | 🟠 |
| 1.8 | ✅ **Fixed** (`version-5`, ⚠️ no automated test — needs a WebAuthn authenticator fixture) — was *already* remove-before-validate; added an atomic `claimOnce` gate to close the get+remove race | `sessions/.../WebAuthNProofEndpoints.kt` | 🟠 |
| 1.9 | ⏸️ **Deferred (TODO in code)** — audit was stale: webauthn4j *already* throws on rollback (nonzero case). Local expert flagged significant nuance (passkey lock-out, clone detection, multi-device); intentionally left for expert review. TODO added at `WebAuthNProofEndpoints.kt` sign-count update. | `sessions/.../WebAuthNProofEndpoints.kt` | 🟡 |
| 1.10 | ✅ **Fixed + tested** (`version-5`) — Signed proofs replayable within validity window → now consumed single-use (atomic `claimOnce` on a signature fingerprint) at session creation only; `proofsCheck` stays freely re-callable. Required adding a `cache` param to `AuthEndpoints` (**breaking constructor change** — see note). | `sessions/.../AuthEndpoints.kt` | ⚪ |

**Fixes:** 1.1 → compare with `MessageDigest.isEqual` on raw bytes. 1.2 → log session id only. 1.3 → assert `claims.iss == issuer()`. 1.4 → ❌ won't-fix (by design, see row). 1.5 → ✅ done: client secrets now use `fastHash` (removes the PBKDF2 cost) and `/token`, `/introspect`, `/revoke` are wrapped in `constrainAttemptRate` (as PIN/password endpoints do). 1.6 → ❌ not an issue (proof-gated, see row). 1.7 → mark consumed TOTP codes in cache. 1.8 → remove challenge from cache before validating. 1.9 → reject authentications whose sign-count ≤ stored. 1.10 → **consume proofs single-use only at the state-changing step (session creation), not in the read-only `proofsCheck`.** Cache a hash of each consumed proof's signature with TTL = its remaining lifetime, and reject re-presentation at login. This keeps `proofsCheck` freely re-callable across the multi-step flow (it creates nothing, so replay is harmless) while killing the meaningful replay — minting a session from a stolen proof. Pair with short proof expiries for PIN/SMS and, where a binding secret is available, bind proofs to a known-device secret.

**Verified-correct (no action):** JWT algorithm pinning / `alg=none` rejection, Apple JWKS verification, OIDC `redirect_uri` exact-match allowlist + HTTPS/loopback enforcement, authorization-code single-use + short TTL + PKCE-binding, PKCE S256 verification, session-secret entropy & hashed storage.

---

## 2. Reliability

| # | Finding | Location | Severity |
|---|---------|----------|----------|
| 2.1 | `metrics-cloudwatch` & `exceptions-sentry` (SA) and `server-sentry`/`server-sentry9` (LS) are empty stubs; `SettingContext.report{}` defaults to no-op → exceptions vanish in production | `service-abstractions/basis/.../SettingContext.kt:161`; module dirs | 🟠 |
| 2.2 | SQL/Postgres have no connection pool — Exposed `connect(url,…)` opens a fresh connection per transaction (KDoc claiming pooling is misleading) | `service-abstractions/database-sql/.../SqlDatabase.kt:62`, `database-postgres/.../PostgresDatabase.kt:98` | 🟠 |
| 2.3 | Ktor & JDK engines: no request timeout (only Netty has a hardcoded idle timeout) | `engine-ktor/.../KtorEngine.kt`, `engine-jdk-server/.../JdkEngine.kt`; `engine-netty/.../NettyEngine.kt:213` | 🟠 |
| 2.4 | Ktor & JDK engines: no graceful shutdown / SIGTERM drain (only Netty) → dropped in-flight requests on rolling deploys | `KtorEngine.kt:309-320`, `JdkEngine.kt` | 🟠 |
| 2.5 | Request body size limit only on Netty (16 MiB); Ktor & JDK unbounded → OOM | `engine-netty/.../NettyRuntimeSettings.kt:33`; `JdkEngine.kt:196` | 🟠 |
| 2.6 | JDK engine runs handlers via `runBlocking` on the default serial executor → effectively single-threaded, trivially DoS-able | `engine-jdk-server/.../JdkEngine.kt:92,115` | 🟠 |
| 2.7 | AWS CRT HTTP clients have no timeout / concurrency config → a hung S3/DynamoDB endpoint stalls callers indefinitely | `service-abstractions/aws-client/.../AwsConnections.kt:83` | 🟡 |
| 2.8 | `runBlocking` inside serialization paths (S3 signed-URL / virus-scan) blocks dispatcher/event-loop threads | `service-abstractions/files-s3/.../S3FileObject.kt`, `files/.../ExternalServerFileSerialization.kt:165` | 🟡 |
| 2.9 | DynamoDB cache `add` lost-update at expiry boundary (overwrites instead of re-incrementing) | `service-abstractions/cache-dynamodb/.../DynamoDbCache.kt:312` | 🟡 |
| 2.10 | WebSocket inbound channels are `Channel.UNLIMITED` → no backpressure, unbounded memory | `KtorEngine.kt:195`, `NettyEngine.kt:431` | 🟡 |
| 2.11 | ~~`ready()` throws raw `Error` (not `Exception`)~~ | `core/.../settings/ServerSettings.kt:223` | — | ❌ **Won't-fix (by design).** A failed-preload is a non-recoverable config/deploy error; `Error` is deliberately chosen so it is *not* caught by normal `catch (Exception)` and the process crashes hard with a stack trace. That's the intended fail-fast. |

**Fixes:** 2.1 → implement or remove the stub modules; ship a real `report` override. 2.2 → wire HikariCP `DataSource`. 2.3–2.6 → add configurable request timeout, graceful shutdown hooks, max body size, and a real thread-pool executor across Ktor/JDK engines. 2.7 → set `apiCallTimeout`/`maxConcurrency` on the AWS clients. 2.8 → make `signedUrl`/`assertSignatureValid` suspend. 2.9 → retry the atomic update instead of blind `set`. 2.10 → bounded channel with backpressure. 2.11 → ❌ won't-fix (intentional uncatchable fail-fast, see row).

---

## 3. Performance

| # | Finding | Location | Severity |
|---|---------|----------|----------|
| 3.1 | Netty buffers entire response into heap (`body.data.bytes()`) → large downloads OOM | `engine-netty/.../NettyEngine.kt:635` | 🟡 |
| 3.2 | Redis `modify` is 2 RTTs/attempt; default `Cache.compareAndSet`/`modify` is a non-atomic read-twice race (safe only because Redis overrides CAS with Lua) | `service-abstractions/cache/.../Cache.kt:135-165` | 🟡 |
| 3.3 | No batch/multi-key cache ops (`MGET`/`MSET`/pipelining unused) → N round-trips | `service-abstractions/cache/.../Cache.kt` | 🟡 |
| 3.4 | ~~Mongo `updateMany`/`deleteMany` (result-returning variants) read the full match set into memory~~ | `service-abstractions/database-mongodb/.../MongoTable.kt:206` | — | ❌ **Won't-fix.** Returning `CollectionChanges` inherently requires materializing the changed docs; the only alternative is streaming (return a `Flow`/cursor), a contract change of dubious value. The `*IgnoringResult`/`*IgnoringOld` variants already exist for callers that don't need the result. |
| 3.5 | Lambda cold start: `loadSettings()` does synchronous serial SecretsManager/S3 fetches in `init` | `engine-aws-serverless/.../AwsAdapter.kt:63` | ⚪ |
| 3.6 | Gradle configuration-cache not enabled (parallel already on) | `gradle.properties` | ⚪ |

---

## 4. Observability

Tracing is strong (pervasive OTel spans across S3/Redis/Mongo/DynamoDB/FCM/Twilio/ClamAV with sanitized attributes; LS instruments HTTP/WS/tasks/schedules and exposes health/readiness endpoints). The gaps:

| # | Finding | Location | Severity |
|---|---------|----------|----------|
| 4.1 | Metrics essentially absent — a `Meter` is exposed but nothing records counters/histograms/gauges (no cache hit-ratio, op-latency, pool-utilization) | `service-abstractions` service impls | 🟠 |
| 4.2 | No correlation/trace ID in error responses — generic "unknown error" with no reference | `core/.../http/DefaultExceptionHttpHandler.kt:39-43` | 🟡 |
| 4.3 | Access logging unstructured & unconditional (plain string, no method/status/duration fields, not toggleable) | `core/.../runtime/implementationHelpers.kt:50` | 🟡 |
| 4.4 | Decorative health checks — FCM always OK; AWS pool-utilization driven by `used` which is never incremented (always reports OK) | `service-abstractions/notifications-fcm/.../FcmNotificationClient.kt:385`, `aws-client/.../AwsConnections.kt:94` | 🟡 |
| 4.5 | ✅ **Confirmed** — MongoDB command spans mis-parent / orphan: the OTel command-listener parents to `Context.current()` on the driver's async I/O thread, but no `ContextProvider` is set and the caller's OTel Context isn't bridged into the reactive subscriber context, so Mongo spans don't nest under the request span. **Lettuce/Redis shares the same risk class.** SQL & in-memory are correct (manual span + `asContextElement`). | `service-abstractions/database-mongodb/.../MongoDatabase.kt:205-220`; ref-correct: `database-sql/.../SqlCollection.kt:47-60`, `database/.../Tracing.jvm.kt:45-49` | 🟠 |

**Fixes:** 4.1 → record RED metrics + cache hit-ratio + pool gauges once a metrics backend exists. 4.2 → inject OTel trace ID into the `LSError` body and access log. 4.3 → structured access log with fields, toggleable. 4.4 → implement real FCM check; fix or remove the always-zero `used` signal. 4.5 → set a `ReactiveContextProvider` on `MongoClientSettings` that reads the OTel Context from the Reactor subscriber context, *and* ensure the caller's OTel Context reaches that context (via `asContextElement`); or mirror the SQL approach — wrap each Mongo op in a manual span with `asContextElement`. Apply the same to Lettuce. (Reporter observed this on a slightly older build; confirmed still present in current code.)

---

## 5. Downstream deploy-confidence (the primary ask)

**Question:** how can a downstream app developer confirm a deployment will succeed *before* routing traffic?

**Core finding:** the building blocks exist (`Service.connect()`/`healthCheck()` on every service; `/meta/health` already runs them all; the AWS CRaC path already does a connect-everything loop) but they are never assembled into a port-less, exit-code-bearing pre-traffic gate. A green `ready()` proves config *parses and constructs* — not that services are *reachable*. Service clients are all `lazy {}`, so a bad URL/credential/secret is first discovered on the first request that touches it, **after** traffic is routed.

### 5.1 What exists today

| Capability | Status | Location |
|---|---|---|
| Parse settings file + required-key check (no server) | ✅ | `core/.../settings/ServerSettings.ext.kt:108-148` |
| Construct/validate setting objects fail-fast (conflicting names, circular overrides, unregistered settings) | ✅ | `core/.../settings/ServerSettings.kt:202-279` |
| Connect/verify all services before serving | ❌ (only AWS Lambda CRaC) | feasibility proven at `engine-aws-serverless/.../AwsAdapter.kt:182-214` |
| `check`/`validate`/`preflight`/`migrate` CLI command | ❌ | CLI is a flat function list, `demo/.../main.kt:54-58` |
| Liveness endpoint | ✅ | `typed/.../MetaEndpoints.kt:58` |
| Readiness with real dependency checks | ✅ diagnostic `/meta/health` — 200 + per-feature body is **correct by design** (200 = the check *ran*; unhealthy is reported in the body; a non-200 means health-checking itself failed). Orchestrator status-code gating is a separate concern → new `/meta/ready` (5.3.3). | `typed/.../MetaEndpoints.kt:88-129` |
| JSON Schema export for `settings.json` | ❌ (schema builder wired to API models only) | — |
| `terraform plan`/`validate` gate before apply | ❌ (`deploy(autoApprove=true)`) | `demo/.../deploy.kt:120` |
| Pre-deploy secret-presence verification | ✅ largely covered — the interactive deploy CLI prompts for any missing secret before deploy. Residual gap only for non-interactive/CI deploys. | `engine-aws-serverless/.../AwsAdapter.kt:89-91` |
| Pre-deploy IAM permission verification | ❌ (policies generated but not simulated against the deploying principal) | `engine-aws-serverless/.../terraform` |
| API/SDK backward-compat diffing | ❌ (kschema/OpenAPI exist but undiffed) | `typed/.../sdk/SDK.kt` |
| DB schema/index migration dry-run/diff | ❌ (module empty & disabled) | `service-abstractions/settings.gradle.kts:37` |

### 5.2 Health-check reality (what's safe to gate on)

| Service | Health check does… | Preflight-safe? |
|---|---|---|
| Mongo / SQL / Postgres | real upsert+read round-trip | ✅ |
| Cassandra | `SELECT now() FROM system.local` | ✅ (non-destructive) |
| Cache (Redis / DynamoDB) | real set+get | ✅ |
| S3 | write→read→GET→delete (validates bucket perms) | ✅ (creates/deletes temp object) |
| PubSub (Redis / DynamoDB) | real publish | ✅ |
| Twilio SMS | read-only account GET | ✅ |
| ClamAV | reachability ping | ✅ |
| **Email (SMTP/SES/Mailgun)** | **sends a real email** to `health-check@example.com` (`EmailService.kt:171`) | ⚠️ unsafe as a gate (sender-reputation/bounces) |
| **FCM notifications** | **decorative — always OK** (`FcmNotificationClient.kt:385`) | ❌ blind (bad creds pass) |

### 5.3 Recommended capabilities (ordered by leverage)

1. **`preflight` / `check` command — highest value, mostly assembly.** A framework helper `engine.preflight(settingsFile): Int` that, without binding a port: runs `loadFromFile` + `ready()`, iterates `settings.allGoals()`, casts each to `Service`, calls `connect()`/verify + `disconnect()` with a timeout (the loop already in `AwsAdapter.kt:182-200`), prints a per-service OK/FAIL table, and **exits non-zero** on any failure. Register in `cli(available=...)` so CI runs `app check` before flipping traffic.
2. **Split `healthCheck()` into a side-effect-free `verify()`** (read-only credential/connectivity probe) distinct from liveness. Use cheap read-only calls: S3 `HeadBucket`, SES `GetSendQuota`/`GetIdentityVerificationAttributes`, FCM dry-run send (`validateOnly=true`), Twilio account GET, Mongo `ping`, SQL `SELECT 1`. Makes #1 safe to run repeatedly and **fixes the FCM blind spot**.
3. ✅ **Approved — new `/meta/ready` endpoint** (leave `/meta/health` at 200, it's the diagnostic). The new endpoint returns **503 when any feature isn't OK** so k8s/ECS/CodeDeploy can gate on the status code. Per-feature level is already computed at `MetaEndpoints.kt:88-129`.
4. ⏸️ **Deferred — `Database.prepareAll()` / `Table.ensureIndexes()`.** A larger plan exists for this; it requires enumerating all tables at build time (its own major refactor / table registry). Not pursued now.
5. **Settings JSON-Schema export.** A `settings-schema` command emitting a JSON Schema for `settings.json` so editors/CI validate the file — and catch typo'd keys that `ignoreUnknownKeys=true` (`ServerSettings.ext.kt:180`) currently swallows.
6. **AWS deploy preflight.** Before `deploy(autoApprove=true)`: run `terraform validate` + `plan` with explicit approval (or `--plan-only`); optionally `iam simulate-principal-policy` against the generated `policyStatements`. (Secret-presence is **already handled** by the interactive deploy CLI prompt — only non-interactive/CI deploys would need an explicit check.)
7. ⭐ **Approved & prioritized — API backward-compat check.** An `api-check` command diffing current kschema/OpenAPI against a stored baseline and failing on breaking changes (removed/renamed fields, type changes, removed endpoints). The user is keen on this. (Migration dry-run is folded into the deferred #4.)

Items **1–3** deliver a genuine "will this deploy work" gate largely by wiring up code that already exists.

---

## 6. Library CI / release hygiene (Lightning Server's own pipeline)

Distinct from §5 — this is about the framework's *own* repo, not downstream apps.

| # | Finding | Location |
|---|---------|----------|
| 6.1 | No static analysis (detekt/ktlint/spotless) — only `explicitApi()` + compiler | repo-wide |
| 6.2 | No dependency/CVE scanning for a security-sensitive auth framework. RESOLVED via OWASP dependency-check report-only (advisory CI job, warns but never gates, no auto-update). Dependabot/renovate deliberately rejected as supply-chain risk. | — |
| 6.3 | No secret scanning (gitleaks/trufflehog) — would have caught §0.2 | — |
| 6.4 | Kover configured for reports only — no `koverVerify` bound, never invoked in CI | `build.gradle.kts:33-37` |
| 6.5 | CI runs `test` only — never `check`/`assemble` (JS/native compile + `apiCheck` unexercised on PRs) | `.github/workflows/testPR.yml` |
| 6.6 | CI hardening: no `permissions:`/`concurrency:`/`timeout-minutes:`; actions pinned to mutable `@v4`; `publishCentral.yml` triggers on **any** tag (`tags: ['*']`) → accidental tag publishes to Maven Central | `.github/workflows/*` |

Versions are current (Gradle 9.4.1, Kotlin 2.3.21, Ktor 3.3.3) — good.

---

## Suggested order of attack

1. **§0 fix-now batch** — small, high-severity, self-contained.
2. **§5 items 1–3** — the preflight command + `verify()` split + readiness status code (the deploy-confidence core).
3. **§6 CI safety net** — `master` trigger, detekt, OWASP dependency-check (report-only), gitleaks, Kover gate.
4. **§2 engine reliability** — timeouts, max body size, graceful shutdown, JDK executor.
5. **§4/§2.1 observability backends** — implement or delete the empty Sentry/CloudWatch modules; wire `report{}`; add metrics.
6. **§2.2 / §2.7** — SQL connection pooling + AWS client timeouts.
