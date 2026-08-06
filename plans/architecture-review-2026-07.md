# Lightning Server v5 — Architecture Review & Roadmap

**Date:** 2026-07-06
**Scope:** Full-repo design review (core, typed, auth/sessions, engines, deployment, data layer boundary, peripheral modules, build/docs/test health). Complements the existing `todo.md`.

---

## Overall Assessment

The framework's fundamentals are strong: the definition→build()→runtime lifecycle is clean and immutable-after-build, the type-safe path DSL and Condition/Modification query DSL are genuinely good, the service-abstractions boundary is unidirectional and tidy, crypto choices in auth are correct (constant-time comparisons, PBKDF2 for passwords, fast hash only for high-entropy secrets), and the recent OpenTelemetry work is solid. The weak points cluster in five places:

1. **Security hardening gaps** — OAuth lacks PKCE and real state/CSRF validation; no engine emits the security headers `expectations.md` itself requires; JSON parsing is lenient with no size/depth limits.
2. **No cross-engine conformance suite** — four engines each hand-roll request/response translation (~800 LOC duplicated) with no shared test asserting they behave identically per `expectations.md`.
3. **AWS serverless reliability** — scheduled tasks have no distributed lock (unlike LocalEngine), async tasks are fire-and-forget Lambda invokes with no retry/DLQ, and Terraform is generated via string templating.
4. **SDK generators are fragile** — ~900 LOC of hand-indented string building, silent skips on unmappable types, snapshot-only tests.
5. **Repo/docs hygiene** — ~15 empty legacy `server-*` dirs, `local/` with sensitive files tracked, `files.md` documents the v4 API, `websockets.md` is a 5-line stub, coverage gate set to 0%.

---

## Detailed Critique by Area

### Core (`core`, `core-shared`)

**Good:** Immutable ServerDefinition with duplicate-endpoint detection; type-safe PathSpec0–3/Many hierarchy; settings two-phase lifecycle with circular-dependency detection; exception responses flow back through interceptors so CORS applies to errors; HEAD→GET fallback and trailing-slash redirects.

**Issues:**
- `Serialization.kt:50,63,76` — all JSON instances use `isLenient = true`; no nesting-depth or payload-size limits on deserialization (DoS surface). Provide strict instances for external input.
- `ServerSettings.kt:91-111` — settings registry is `MapRegistry<ServerSetting<*,*>, Any?>` with unchecked casts; wrong-typed config fails deep in transformation instead of at registration. Also `goal.getOrRegister()` during `get()` is not synchronized (concurrent double-transform possible).
- `ServerDefinition.kt:147-148` — serializersModule lambdas are evaluated lazily; a throwing module getter isn't caught until first handler use. Evaluate eagerly in `build()`.
- `ServerDefinition.kt:175-182` — HTTP method merging on path collision is silent while websocket collision throws; asymmetric and undocumented.
- Interceptor ordering is implicit registration order with no priority mechanism; `HttpInterceptor.compileAndInstrument()` (`HttpInterceptor.kt:102-117`) uses a fragile `reduceIndexed` idx==1 special case.
- No typed query-parameter extraction (path args are typed, query params are stringly-typed maps).
- Request bodies are fully buffered; no streaming request path or multipart parsing.
- No response invariant validation (204-with-body, 3xx-without-Location).
- `Runtime.Cached` (`ServerSetting.kt:95-114`) keys its cache on a weak reference to the runtime — cache silently evaporates under GC.

### Typed endpoints & SDK generation (`typed`, `typed-shared`)

**Good:** Ergonomic reified `api()` DSL; comprehensive JSON Schema/OpenAPI generation with annotation extension points; W6 warnings for undeclared thrown errors; ModelRestEndpoints generates a full CRUD surface with scoped auth.

**Issues:**
- `OpenApi.kt:223` (TODO) — declared `errorCases` never make it into OpenAPI error responses; clients can't see error schemas. Path parameters emitted as an empty list (`OpenApi.kt:~300`).
- GET/HEAD inputs deserialize from query params with no validation that the input type is query-representable; complex inputs fail silently.
- Both SDK generators (`TypescriptFetcherSdk.kt` ~540 LOC, `FetcherSdk.kt` ~320 LOC) are raw `Appendable` string builders with manual indent tracking; generic substitution via string `.replace()` can false-match substring type names; unmappable serializers are skipped silently (`continue`) instead of failing the build.
- SDK tests are snapshot-only; no unit tests for type-mapping (`tsType()`, `kotlinTypeString()`, `kotlinSerializer()` spread across files with no single source of truth).
- ModelRestEndpoints declares mostly-empty `errorCases` while actually throwing (uniqueness violations → W6 spam); no pagination metadata; bulk operations non-atomic.

### Auth & sessions

**Good:** Session secrets random (24 bytes), stored only hashed, constant-time comparison; lazy hash migration; single-use PIN with attempt caps and PBKDF2; Apple JWT verification validates signature/issuer/audience/expiry before trust; masquerade is default-deny with preserved auth chain.

**Issues (ordered by severity):**
- **No PKCE** anywhere in sessions-oauth (verified: zero hits for `code_challenge`).
- **OAuth state is `Uuid.random()` generated per `/open` call and not stored/validated on callback** (`OauthProofEndpoints.kt:~105`) — no real CSRF binding.
- No redirect-URI whitelist validation on callback.
- Rate limiting (`Cache.ext.kt:49-78` `constrainAttemptRate`) is a flat counter+block window — no exponential backoff, no per-IP dimension; block-window resets enable slow brute-force.
- No refresh-token rotation; every refresh writes session metadata to DB (scaling concern).
- Masqueraded sessions inherit the full parent scope set; should be restrictable to a subset. No audit logging of masquerade or auth events generally.
- `sessions-openid-provider` is a stub (not even in settings.gradle.kts) — delete or finish.
- Password hashing is PBKDF2-100k; consider Argon2id longer-term.

### Engines & deployment

**Good:** Clean `ServerRuntime` interface; LocalEngine shares scheduling (with cache-based distributed lock + TTL), graceful shutdown with drain, websocket backpressure via bounded channels; CRaC gives Lambda ~100-200ms restores.

**Issues:**
- **No engine emits `X-Content-Type-Options: nosniff` or HSTS** despite `expectations.md` requiring both (verified — only the header-name constant exists). One `SecurityHeadersInterceptor` in core fixes all engines at once.
- **No shared conformance test suite** run against all four engines; per-engine reliability tests exist but expectations.md compliance is untested.
- ~800 LOC of duplicated request/response translation across Ktor/Netty/JDK/AWS adapters; no `HttpRequestAdapter`-style shared layer for path/header/body/real-IP extraction.
- **AWS schedules have no distributed lock** (`AwsAdapterSchedule.kt`) — overlapping EventBridge fires run concurrently, unlike LocalEngine.
- **AWS tasks are `Lambda.Invoke(Event)` fire-and-forget** — no retry, no DLQ, no queue.
- AWS websocket state does an optimistic-lock CAS loop against DynamoDB capped at 50 attempts, with a comment acknowledging non-deterministic serialization can thrash it (`AwsAdapterWs.kt:~88`) — needs canonical serialization.
- Terraform generation (~1,200 LOC in engine-aws-serverless, ~200 in deploy-aws-ec2) is string templating: no resource-reference safety, errors surface at `terraform apply`.
- No standard `/health` or `/metrics` endpoint convention (each app hand-rolls).
- Range: parsing utilities exist in `files/ranging.kt` but Range/Accept-Ranges are not honored engine-wide.

### Data layer boundary (with service-abstractions)

**Good:** Clean unidirectional dependency; rich Condition/Modification DSL (geo, full-text, bitwise, per-element); ModelPermissions gives row-level + field-level (mask) + update-restriction security, serializable so clients can introspect capabilities.

**Issues:**
- **No transaction API** in the public `Database` interface (acknowledged TODO at `Database.kt:408-413`); multi-table endpoint operations have no atomicity story.
- **No migration framework** — Postgres impl explicitly punts to Flyway/Liquibase; the safe-evolution pattern (nullable-first fields) is convention, not tooling. No schema-drift detection.
- Postgres stores models as JSONB only — no native columns, weak indexing on nested fields; "partial" is accurate.
- Missing DSL ops: compare-and-swap (optimistic locking), list insert-at-index, collection isEmpty (old op deprecated with no replacement, `Condition.kt:393-403`), limited aggregations (no Min/Max).
- URL-scheme service wiring validates only at runtime; a bad URL fails on first use rather than at startup.

### Repo hygiene, build, docs, tests

- ~15 `server-*` directories are empty v4 remnants; `server/`, `processor/`, `coroutine-websockets/` have no build files. Delete.
- `local/` contains logs/notes/keys (e.g. `openaikey.txt`) — should be untracked; `site/` (built MkDocs) shouldn't live on the main branch.
- `files.md` documents the v4 API (marked OUT OF DATE); `websockets.md` is a 5-line TODO stub; no v4→v5 migration guide in docs/ (only in plans/). The `docs-guide` drift-checked-examples module is excellent — extend its coverage.
- Kover verify minBound is 0 (no gate); detekt/CVE scanning are report-only.
- `demo/` has 20 source files but only 5 test files — weak as the reference implementation.
- `secret-source-aws` has zero tests and 7 embedded TODOs.

---

## Recommended Roadmap

### Phase 1 — Security & correctness (do first)

1. **OAuth hardening (sessions-oauth):** implement PKCE (RFC 7636); persist `state` (cache/cookie) and validate on callback; add redirect-URI whitelist validation.
2. **SecurityHeadersInterceptor in core:** `X-Content-Type-Options: nosniff` always, HSTS when public URL is https, wired by default; conformance-tested. (Closes expectations.md gap for all engines at once.)
3. **JSON input hardening:** strict (non-lenient) Json for external request bodies + configurable max payload size / nesting depth; keep lenient variant for settings files.
4. **AWS schedule locking:** port LocalEngine's cache `setIfNotExists` lock pattern to `AwsAdapterSchedule` (DynamoDB or cache-backed).
5. **Auth audit logging:** structured events for login/failure/refresh/masquerade; masquerade scope restriction (subset of parent scopes).
6. **Rate limiting upgrade:** exponential backoff in `constrainAttemptRate`, optional per-IP key dimension.
7. Settings type safety: validate types at `register()` time; make `goal` map access thread-safe; eagerly evaluate serializersModule getters in `build()`.

### Phase 2 — Reliability & testing infrastructure

8. **EngineConformanceTest:** one shared suite (HEAD fallback, OPTIONS, CORS, security headers, timeout, body limits, trailing slash, Range on files) parameterized over Ktor/Netty/JDK/AWS-local. This is the highest-leverage test investment in the repo.
9. **AWS task reliability:** SQS-backed task queue (or at minimum Lambda DLQ + retry policy) replacing bare `Invoke(Event)`.
10. **Canonical serialization for AWS websocket state** to stop CAS-loop thrash.
11. **Raise the coverage gate** from 0% to a real baseline and ratchet; add tests to `secret-source-aws` (currently zero) and demo (integration tests for the blog flows).
12. **OpenAPI completeness:** emit declared errorCases as documented error responses (`OpenApi.kt:223` TODO); emit path parameters; fill in ModelRestEndpoints errorCases (kills W6 spam).
13. Standard `/health` + `/metrics` meta-endpoints module.
14. Transaction API on `Database` (`suspend fun <R> transaction(block): R`), backend-optional with clear capability signaling — the acknowledged TODO.

### Phase 3 — Maintainability & DX

15. **Refactor SDK generators** onto a small code-model/AST layer (or KotlinPoet for the Kotlin SDK): centralize type mapping in one `TypeMapping` unit, fail loudly on unmappable types, add unit tests per type-mapping case alongside snapshots.
16. **Extract shared engine adapter layer** (request/response translation helpers: path, headers, real-IP, body streaming) to delete ~800 duplicated LOC across the four engines.
17. **Repo cleanup:** delete empty `server-*`, `server/`, `processor/`, `coroutine-websockets/` dirs (or include+finish); untrack `local/` and `site/`; decide fate of `sessions-openid-provider`.
18. **Docs sprint:** rewrite `files.md` for v5; write `websockets.md`; publish v4→v5 migration guide; extend docs-guide drift-checked examples to files/media/websockets.
19. Typed query parameters (QuerySpec analog to PathSpec) and a typed `bodyAs<T>()` helper.
20. Interceptor ordering: explicit priority or documented phases; simplify `compileAndInstrument`.
21. Streaming request bodies + multipart support (unlocks large uploads without buffering).

### Phase 4 — Strategic / larger bets

22. **Migration tooling:** even minimal — schema fingerprint per model stored in DB, startup drift warning, and documented nullable-first evolution recipe; Flyway hook for Postgres users.
23. **Postgres maturity:** optional native-column mapping for top-level scalar fields (indexable) with JSONB for nested structure; or explicitly position Postgres as JSONB-only and document the tradeoff prominently.
24. **Terraform generation safety:** evaluate cdktf/Pulumi or add an HCL-validation test step; at minimum validate generated JSON with `terraform validate` in CI.
25. Refresh-token rotation + concurrent-session limits + logout-all-devices cascade.
26. Argon2id option for password hashing.
27. DSL gaps: compare-and-swap modification, list insert-at-index, collection isEmpty condition, Min/Max aggregates.
28. Optimistic locking / ETag support in ModelRestEndpoints; pagination metadata (total count / next-page hints).

---

## Cross-reference with existing `todo.md`

The existing todo list (header parsing, ServerSetting thread safety, StartupTask circular deps, CORS per-domain, secret rotation prep, websocket rate limiting) remains valid; items 6, 7, and the auth-module suggestions there overlap Phase 1–2 above. This review adds the OAuth/PKCE, conformance-suite, AWS-reliability, and SDK-generator items as the biggest previously-untracked gaps.
