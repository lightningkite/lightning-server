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

- Added `Session.authenticatedAt: Instant?` and an `authenticatedAt` parameter on `SessionManager.newSession` (defaults to now). `Session.toAuth()` now uses it for `Authentication.issuedAt`, falling back to `createdAt` when it is null (sessions stored before this release). It is nullable so Postgres can add the column to an existing session table.

### Behavior

- **Security: sub-sessions no longer reset the re-authentication clock.** A sub-session inherits its parent's `authenticatedAt`, so `maxAge` requirements (e.g. the 10-minute gate on proof-method credential management) measure time since the user actually proved identity rather than since the sub-session was minted.
- **Security: sub-session scopes must be within the parent's.** `POST /sub-session` now responds 403 when the requested scopes are not all met by the parent session's scopes, and 401 when the parent session has been terminated. Omitting `scopes` (the default, root) now grants the parent's scopes rather than root, so callers of a narrower-scoped session that omit scopes keep working.
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
- **Security: masquerading no longer resets the re-authentication clock.** The `X-Masquerade` authentication now keeps the original authentication's `issuedAt`, so an old token plus the header no longer passes `maxAge` requirements.
- **`/meta/openapi` (Swagger UI) loads the spec from `openapi.json`** instead of inlining the JSON into a `<script>` block, so descriptions containing `</script>` can no longer break out of it.
- **HTTP root span uses route pattern.** `ServerRuntime.handle()` now creates a single root span named `"<METHOD> <route-pattern>"` (e.g. `GET /users/{id}`) at the top of the handler, with standard `http.*` attributes. The previous `handleWithMetrics` helper has been folded into `handle()`; metrics recording, exception handling, and gzip negotiation all happen inside this root-span scope, so interceptor and exception-handler spans nest correctly.
- **Unmatched HTTP requests are now traced.** They produce a root span named after the literal target instead of being silently uninstrumented.
- **HTTP metrics on the exception path now carry the real route.** The route pattern is resolved once at the top of `handle()` and reused for both success and failure metrics/spans, so failed requests no longer record `route = "unknown"`.
- **Ktor WebSocket lifecycle is now traced.** The Ktor engine routes `willConnect` / `didConnect` / `messageFromClient` / `disconnect` through the `*WithMetrics` wrappers, matching the instrumentation of the other engines.
- **Scheduled tasks emit OTel spans.** `LocalEngine` now wraps scheduled-task polling and execution in `schedule.poll <name>` and `schedule.tick <name>` spans, with `schedule.name` and `schedule.lockHeld` attributes.

### Tests

- Added `SessionManagerTest` cases covering sub-session `maxAge` inheritance through the refresh-to-access-token flow, legacy sessions without `authenticatedAt`, cascading termination and deletion (directly and through a superuser's REST update and delete), sub-sessions from terminated parents, and sub-session scope defaulting and escalation.
- Added an `AuthenticationCacheKeyTest` case verifying a masqueraded authentication keeps the original `issuedAt`.
- Added `MetaEndpointsTest` in `typed` verifying `/meta/ws-tester` does not reflect its `path` query parameter and is served with `frame-ancestors 'none'`.
- Added test-only `InMemoryTelemetry` helper that registers a `"memory"` URL scheme on `OpenTelemetrySettings` backed by `InMemorySpanExporter`, so tests can configure `telemetry { url = "memory" }` and inspect captured spans.
- Added `HttpSpanTest` in `core` verifying the root-span name, `http.*` attributes, interceptor nesting (e.g. CORS), and unmatched-route behavior of `ServerRuntime.handle()`.
- Added `HttpSpanTest` in `engine-jdk-server` as an end-to-end smoke test confirming the JDK engine produces a route-pattern root span.
- Added `WebSocketSpanTest` in `engine-ktor` and `engine-netty` verifying that WebSocket lifecycle events flow through the `*WithMetrics` wrappers and emit corresponding spans.

### Build

- Added `io.opentelemetry:opentelemetry-sdk-testing` (1.60.1) to the test classpaths of `core`, `engine-ktor`, `engine-netty`, and `engine-jdk-server`.
