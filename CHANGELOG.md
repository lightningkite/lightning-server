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

_No public API changes._

### Behavior

- **HTTP root span uses route pattern.** `ServerRuntime.handle()` now creates a single root span named `"<METHOD> <route-pattern>"` (e.g. `GET /users/{id}`) at the top of the handler, with standard `http.*` attributes. The previous `handleWithMetrics` helper has been folded into `handle()`; metrics recording, exception handling, and gzip negotiation all happen inside this root-span scope, so interceptor and exception-handler spans nest correctly.
- **Unmatched HTTP requests are now traced.** They produce a root span named after the literal target instead of being silently uninstrumented.
- **HTTP metrics on the exception path now carry the real route.** The route pattern is resolved once at the top of `handle()` and reused for both success and failure metrics/spans, so failed requests no longer record `route = "unknown"`.
- **Ktor WebSocket lifecycle is now traced.** The Ktor engine routes `willConnect` / `didConnect` / `messageFromClient` / `disconnect` through the `*WithMetrics` wrappers, matching the instrumentation of the other engines.
- **Scheduled tasks emit OTel spans.** `LocalEngine` now wraps scheduled-task polling and execution in `schedule.poll <name>` and `schedule.tick <name>` spans, with `schedule.name` and `schedule.lockHeld` attributes.

### Tests

- Added test-only `InMemoryTelemetry` helper that registers a `"memory"` URL scheme on `OpenTelemetrySettings` backed by `InMemorySpanExporter`, so tests can configure `telemetry { url = "memory" }` and inspect captured spans.
- Added `HttpSpanTest` in `core` verifying the root-span name, `http.*` attributes, interceptor nesting (e.g. CORS), and unmatched-route behavior of `ServerRuntime.handle()`.
- Added `HttpSpanTest` in `engine-jdk-server` as an end-to-end smoke test confirming the JDK engine produces a route-pattern root span.
- Added `WebSocketSpanTest` in `engine-ktor` and `engine-netty` verifying that WebSocket lifecycle events flow through the `*WithMetrics` wrappers and emit corresponding spans.

### Build

- Added `io.opentelemetry:opentelemetry-sdk-testing` (1.60.1) to the test classpaths of `core`, `engine-ktor`, `engine-netty`, and `engine-jdk-server`.
