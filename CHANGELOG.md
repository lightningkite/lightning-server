# Changelog

All notable changes to Lightning Server are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Each release groups entries under:

- **API** — public surface changes (added / changed / removed types and functions).
- **Behavior** — runtime behavior changes that do not alter the public surface.
- **Tests** — test-only additions and changes.
- **Build** — build-script, dependency, and tooling changes.

## [Unreleased]

### API

- Added `PinBasedProofEndpoints.ScreeningResult` (`Allow`, `Suppress`, `Reject(message, detail)`) and an open `PinBasedProofEndpoints.screen(to)` hook, called before a PIN is created. `start` responds 400 with the given `message` and `detail` (default `"target-rejected"`) on `Reject`, and lists that error in its `errorCases`.
- `EmailProofEndpoints`: replaced `verifyEmail: (String) -> Boolean` with `screenEmail: (EmailAddress) -> ScreeningResult`, and `emailTemplate` now receives an `EmailAddress` instead of a `String`. A deprecated `EmailProofEndpoints(..., verifyEmail = ...)` overload maps `true`/`false` to `Allow`/`Suppress`.
- `SmsProofEndpoints`: replaced `verifyPhone: (String) -> Boolean` with `screenPhone: (PhoneNumber) -> ScreeningResult`. A deprecated `SmsProofEndpoints(..., verifyPhone = ...)` overload maps `true`/`false` to `Allow`/`Suppress`.
- `WebAuthNProofEndpoints`: renamed the `expiration` parameter to `challengeExpiration`. A deprecated `WebAuthNProofEndpoints(..., expiration = ...)` overload is kept for named-argument callers.
- Added `Session.authenticatedAt: Instant?` and an `authenticatedAt` parameter on `SessionManager.newSession` (defaults to now). `Session.toAuth()` now uses it for `Authentication.sessionIssuedAt`, falling back to `createdAt` when it is null (sessions stored before this release). It is nullable so Postgres can add the column to an existing session table.
- **Breaking:** renamed `Authentication.issuedAt` to `sessionIssuedAt` (when the subject authenticated; `maxAge` measures from it), including the `Authentication(principalType, id, ...)` factory parameter. The `Authentication(principalType, subject, ...)` and session `Authentication(..., sessionId: Uuid?, ...)` factories and `testAuth` keep their `issuedAt` parameter, which now sets `sessionIssuedAt`.
- Added `Authentication.tokenIssuedAt: Instant?`, when the access token was minted (null when the authentication didn't come from an access token), and a `tokenIssuedAt` parameter on the factory and on `Authentication.copy`. All built-in token formats set it on `create`.
- **Breaking:** added `TokenFormat.expiration: Duration`. Custom `TokenFormat` implementations must provide it.
- **Breaking:** `JwtTokenFormat` writes the principal's name to the `prn` header, and `sub` is now just the subject's ID (it used to be prefixed with `<principal name>|`). `JwtHeader` gains a required `prn` and `alg` no longer defaults; `JwtClaims.iat` no longer defaults and `JwtClaims` gains a required `siat`. `JwtClaims.sub` and `scope` are no longer nullable, and the unused `jti`, `userId` and `thp` claims are removed.
- **Breaking:** `AppleJwtVerifier.verifyAppleIdToken` returns the new `AppleIdTokenClaims` instead of `JwtClaims`.
- Added `cache: Runtime<Cache>? = null` and `tokenInvalidationChecks: Boolean = false` to `SessionManager`, and `tokenInvalidationChecks` to `AuthEndpoints`. `SessionManager` throws `IllegalArgumentException` if the checks are on without a cache.
- Added `SessionManager.invalidateSession(sessionId)` and `SessionManager.invalidateSubject(subjectId)`, which reject access tokens minted before the call without ending any session. They only log a warning when `tokenInvalidationChecks` is off.

### Behavior

- **Email and SMS proof screening runs before the PIN is created.** Previously the PIN was created and the `verifyEmail`/`verifyPhone` check ran afterward in `send`. Suppressed requests still create a PIN, so their lookup key behaves exactly like a real one. Invalid email addresses passed to `EmailProofEndpoints.start` now respond 400 before screening.
- **Security: sub-sessions no longer reset the re-authentication clock.** A sub-session inherits its parent's `authenticatedAt`, so `maxAge` requirements (e.g. the 10-minute gate on proof-method credential management) measure time since the user actually proved identity rather than since the sub-session was minted.
- **Security: sub-session scopes must be within the parent's.** `POST /sub-session` now responds 403 when the requested scopes are not all met by the parent session's scopes, and 401 when the parent session has been terminated. Omitting `scopes` (the default, root) now grants the parent's scopes rather than root, so callers of a narrower-scoped session that omit scopes keep working.
- **Opt-in access token revocation.** With `tokenInvalidationChecks = true`, terminating or deleting a session through the session table rejects the live access tokens of it and every session derived from it, and `invalidateSession` / `invalidateSubject` reject a session's or subject's current access tokens. Invalidations live in the cache for one token lifetime; each access-token request reads the cache once or twice and fails if the cache does. A JWT minted in the same second as an invalidation, just after it, is also rejected, because JWT times are whole seconds. Sessions last used more than one token lifetime ago get no cache entry. A cache failure while recording a termination's invalidations is logged and does not stop the termination or its cascade.
- **JWT claims changed.** `iat` is now when the access token was minted (it used to be when the user authenticated), and the authentication time moved to the new `siat` claim. A JWT whose `prn` is not the reading principal's name is ignored (read returns null), and audience or issuer mismatches now throw `JwtException` instead of being ignored. Malformed JWT payloads throw `JwtFormatException`, and the signature is now verified before the claims are parsed.
  - **Upgrading:** access tokens minted before this release are not accepted. JWTs have no `prn` and are ignored, so those requests are unauthenticated. Tiny tokens are expected to fail to decode and get a 401, since the encoded `Authentication` changed shape. Clients get a new access token on their next refresh.
- **Sign in with Apple now passes the user's verified email to `ExternalProfile`.** It was always null before, because the `email` and `email_verified` claims were dropped while decoding the ID token.
- **Tiny tokens that decrypt or verify but don't decode now throw `TokenException`** (a 401) instead of an arbitrary serialization error.
- **Terminating a session terminates its derived sessions.** Terminating or deleting a session through the session table (`sessionInfo.table()`, `table(auth)`, the logout endpoints, a superuser's REST update or delete) now also terminates every session transitively derived from it. Writes through `sessionInfo.baseTable()` or straight to the database do not cascade. Sub-sessions no longer outlive their parent's logout; use a separate session for integrations that must.
  - **Upgrading:** this only applies to terminations from now on. Sub-sessions whose parent was terminated before this release, and sub-sessions minted earlier with broader scopes than their parent, stay valid. To clear them, terminate every existing sub-session once after deploying:
    ```kotlin
    sessions.sessionInfo.table().updateMany(
        condition<Session<User, Uuid>> { (it.derivedFrom neq null) and (it.terminated eq null) },
        modification { it.terminated assign now() },
    )
    ```
- **`Session.derivedFrom` is now indexed** (`@Index`), so the cascade doesn't scan the session table. Mongo creates the index on the collection's first use after deploy; Postgres adds it via its additive schema preparation.
- **Security: fixed reflected XSS in `/meta/ws-tester`.** The `path` query parameter was interpolated raw into the page's HTML; the page's script now reads it from `location.search` and assigns it to the input's `value`. The page's Connect action also ignores paths not starting with `/`, so a crafted `?path=@evil.example/x` link can't send the user's token to another host. The page is also served with `Content-Security-Policy: frame-ancestors 'none'` so it can't be framed for clickjacking.
- **Security: masquerading no longer resets the re-authentication clock.** The `X-Masquerade` authentication now keeps the original authentication's `sessionIssuedAt`, so an old token plus the header no longer passes `maxAge` requirements.
- **`/meta/openapi` (Swagger UI) loads the spec from `openapi.json`** instead of inlining the JSON into a `<script>` block, so descriptions containing `</script>` can no longer break out of it.
- **HTTP root span uses route pattern.** `ServerRuntime.handle()` now creates a single root span named `"<METHOD> <route-pattern>"` (e.g. `GET /users/{id}`) at the top of the handler, with standard `http.*` attributes. The previous `handleWithMetrics` helper has been folded into `handle()`; metrics recording, exception handling, and gzip negotiation all happen inside this root-span scope, so interceptor and exception-handler spans nest correctly.
- **Unmatched HTTP requests are now traced.** They produce a root span named after the literal target instead of being silently uninstrumented.
- **HTTP metrics on the exception path now carry the real route.** The route pattern is resolved once at the top of `handle()` and reused for both success and failure metrics/spans, so failed requests no longer record `route = "unknown"`.
- **Ktor WebSocket lifecycle is now traced.** The Ktor engine routes `willConnect` / `didConnect` / `messageFromClient` / `disconnect` through the `*WithMetrics` wrappers, matching the instrumentation of the other engines.
- **Scheduled tasks emit OTel spans.** `LocalEngine` now wraps scheduled-task polling and execution in `schedule.poll <name>` and `schedule.tick <name>` spans, with `schedule.name` and `schedule.lockHeld` attributes.

### Tests

- Added `EmailProofEndpointsTest` and `SmsProofEndpointsTest` cases covering suppressed and rejected destinations, invalid email addresses, and the deprecated `verifyEmail`/`verifyPhone` overloads.
- Added `SessionManagerTest` cases covering sub-session `maxAge` inheritance through the refresh-to-access-token flow, legacy sessions without `authenticatedAt`, cascading termination and deletion (directly and through a superuser's REST update and delete), sub-sessions from terminated parents, and sub-session scope defaulting and escalation.
- Added an `AuthenticationCacheKeyTest` case verifying a masqueraded authentication keeps the original `sessionIssuedAt`.
- Added `MetaEndpointsTest` in `typed` verifying `/meta/ws-tester` does not reflect its `path` query parameter and is served with `frame-ancestors 'none'`.
- Added test-only `InMemoryTelemetry` helper that registers a `"memory"` URL scheme on `OpenTelemetrySettings` backed by `InMemorySpanExporter`, so tests can configure `telemetry { url = "memory" }` and inspect captured spans.
- Added `HttpSpanTest` in `core` verifying the root-span name, `http.*` attributes, interceptor nesting (e.g. CORS), and unmatched-route behavior of `ServerRuntime.handle()`.
- Added `HttpSpanTest` in `engine-jdk-server` as an end-to-end smoke test confirming the JDK engine produces a route-pattern root span.
- Added `WebSocketSpanTest` in `engine-ktor` and `engine-netty` verifying that WebSocket lifecycle events flow through the `*WithMetrics` wrappers and emit corresponding spans.

### Build

- Added `io.opentelemetry:opentelemetry-sdk-testing` (1.60.1) to the test classpaths of `core`, `engine-ktor`, `engine-netty`, and `engine-jdk-server`.
