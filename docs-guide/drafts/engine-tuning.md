> # ⚠️ UNREVIEWED FIRST DRAFT — DO NOT PUBLISH
> Auto-generated first pass. Code samples are modeled on the current source but have **NOT** been compiled, drift-checked, or reviewed. This page is intentionally **not** in the site nav. Before publishing: port samples into the drift-checked `docs-guide/src/samples` module, run the fresh-eyes judge, and delete this banner.

# Engine Tuning & Reliability

A `ServerBuilder` does not bind any port until you wrap it in an **engine** and call `start()`.  Lightning Server ships three engines that are all suitable for production: `KtorEngine`, `NettyEngine`, and `JdkEngine`.  All three share a common set of reliability knobs (via `EngineReliabilitySettings`) that protect your server from slow clients, oversized payloads, unbounded WebSocket buffering, and ungraceful restarts.

This chapter covers how to choose an engine, what each reliability knob does, and which knobs apply to which engine.

## Imports

All examples in this chapter use the following imports:

```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.engine.ktor.*
import com.lightningkite.lightningserver.engine.netty.*
import com.lightningkite.lightningserver.engine.jdk.*
import com.lightningkite.lightningserver.engine.local.*
import com.lightningkite.services.kfile.KFile
```

## Choosing an Engine

| Engine | Module | `start()` signature | WebSockets | Native transport |
|---|---|---|---|---|
| `KtorEngine` | `engine-ktor` | `start(factory)` e.g. `start(Netty)` | Yes | Via Ktor engine factory |
| `NettyEngine` | `engine-netty` | `start()` | Yes | epoll (Linux) / kqueue (macOS) |
| `JdkEngine` | `engine-jdk-server` | `start()` | **No** | NIO (JDK `HttpServer`) |

**`KtorEngine`** is the recommended starting point.  It wraps Ktor's server framework, which supports multiple transport back-ends via a factory pattern — `Netty`, `CIO`, and `Jetty` from the Ktor ecosystem.  Using `start(io.ktor.server.netty.Netty)` gives you Ktor's Netty integration (HTTP/1.1 + HTTP/2 + WebSockets) with a minimum of setup.

**`NettyEngine`** is Lightning Server's own Netty wrapper, giving you more direct control over Netty configuration (TCP buffer sizes, WebSocket compression, backlog, native transport).  Choose this when you need to tune networking at a lower level than Ktor exposes.

**`JdkEngine`** has zero external HTTP library dependency — it uses the `com.sun.net.httpserver` API built into the JDK.  It is suitable for simple deployments and tools that do not need WebSockets.

### A Real `main()` (Illustrative)

```kotlin
// Illustrative — verified against engine source and CLAUDE.md conventions.
// Imports needed in real code:
//   import com.lightningkite.lightningserver.engine.ktor.KtorEngine
//   import com.lightningkite.lightningserver.settings.loadFromFile
//   import com.lightningkite.services.kfile.KFile
//   import io.ktor.server.netty.Netty

fun main() {
    val built = Server.build()
    KtorEngine(built).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        start(Netty)  // blocks until shutdown
    }
}
```

For `NettyEngine` or `JdkEngine`, `start()` takes no factory argument:

```kotlin
// Illustrative.
fun mainNetty() {
    val built = Server.build()
    NettyEngine(built).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        start()
    }
}

fun mainJdk() {
    val built = Server.build()
    JdkEngine(built).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        start() // note: WebSockets are NOT supported
    }
}
```

## Engine Settings in `settings.json`

Each engine reads its configuration from its own key in `settings.json`:

| Engine | Settings class | JSON key |
|---|---|---|
| `KtorEngine` | `KtorRuntimeSettings` | `"ktorRunConfig"` |
| `NettyEngine` | `NettyRuntimeSettings` | `"nettyRunConfig"` |
| `JdkEngine` | `JdkRuntimeSettings` | `"jdkRunConfig"` |

All three share these top-level fields:

- **`host`** — bind address (default `"0.0.0.0"`)
- **`port`** — listen port (default `8080`)
- **`realIpHeader`** — header to read the client's real IP when behind a proxy, e.g. `"X-Forwarded-For"` or `"X-Real-IP"`

The reliability sub-object is covered below.

## `EngineReliabilitySettings` — The Common Knobs

All three engines embed `EngineReliabilitySettings` under the `reliability` key.  These are the knobs that protect against common failure modes.  Defaults are safe for typical web APIs.

```json
{
  "ktorRunConfig": {
    "host": "0.0.0.0",
    "port": 8080,
    "reliability": {
      "maxBodySize": "16MiB",
      "shutdownDrainTimeout": "25s",
      "webSocketInboundBuffer": 256,
      "webSocketOversizePolicy": "CLOSE",
      "scheduleLockTtl": "1h"
    }
  }
}
```

### `maxBodySize` — Request Body Cap

**Default: 16 MiB**  
**Applies to: Ktor, JDK** (Netty uses `maxAggregatedContentLength` instead — see below)

Requests whose declared `Content-Length` exceeds this cap are rejected with `413 Payload Too Large` before the body is read.  Streamed bodies that grow past the cap mid-read are also rejected.

This is your primary defense against clients that try to exhaust your server's memory by sending enormous uploads.

```json
{ "reliability": { "maxBodySize": "64MiB" } }
```

Use a `DataSize` string: `"16MiB"`, `"512KiB"`, `"1GiB"`, etc.

> **Netty note:** Netty's HTTP aggregator enforces body size via its own `maxAggregatedContentLength` field on `NettyRuntimeSettings`, not via `reliability.maxBodySize`.  Set both if you use `NettyEngine`.

### `idleTimeout` — Idle Connection Reaping

**Default: 120 seconds**  
**Applies to: Netty only** (ignored by Ktor and JDK engines)

How long an idle keep-alive connection may sit with no read/write activity before it is closed.  Without this, a slow trickle of idle connections can exhaust file descriptors.

```json
{ "nettyRunConfig": { "reliability": { "idleTimeout": "60s" } } }
```

### `shutdownDrainTimeout` — Graceful Shutdown

**Default: 25 seconds**  
**Applies to: Ktor, Netty, JDK**

When the JVM receives `SIGTERM` or `SIGINT`, all three engines:

1. Stop accepting new connections.
2. Wait up to `shutdownDrainTimeout` for in-flight requests to complete.
3. Disconnect all configured services.
4. Exit.

No explicit shutdown code is needed in your `main()`.  The shutdown hook is registered automatically by `start()`.

In container environments set this to slightly less than your orchestrator's termination grace period — for Kubernetes the default `terminationGracePeriodSeconds` is 30, so 25 seconds leaves 5 seconds for the JVM to finish cleanly.

```json
{ "reliability": { "shutdownDrainTimeout": "20s" } }
```

### `webSocketInboundBuffer` — WebSocket Backpressure

**Default: 256 frames**  
**Applies to: Ktor, Netty** (JDK engine does not support WebSockets)

Inbound WebSocket frames from the peer are placed in a bounded channel before the handler sees them.  This decouples the socket reader from the handler and provides true backpressure.

If the handler is slower than the peer and the buffer fills up, `webSocketOversizePolicy` decides what happens (see below).

Reduce this if you have many concurrent connections and want to cap per-socket memory.  Increase it if your handler has occasional slow ticks and you do not want to close sockets under brief load spikes.

### `webSocketOversizePolicy` — Buffer Overflow Action

**Default: `"CLOSE"`**  
**Applies to: Ktor, Netty**

What to do when `webSocketInboundBuffer` is full and a new frame arrives:

| Value | Behavior |
|---|---|
| `CLOSE` | Close the socket with WebSocket close code 1009 (message too big). Safe default — the client knows it was closed. |
| `DROP_OLDEST` | Discard the oldest buffered frame to make room. Lossy but keeps the socket alive. |
| `SUSPEND` | Pause reading from the socket until the handler drains a slot. Applies true TCP backpressure. May cause the peer's write buffer to fill if slow. |

```json
{ "reliability": { "webSocketOversizePolicy": "SUSPEND" } }
```

### `workerThreads` — JDK Thread Pool

**Default: `null` (= `availableProcessors() * 2`)**  
**Applies to: JDK only** (Ktor and Netty manage their own event loops)

The JDK engine runs each request on a thread from a bounded `ThreadPoolExecutor`.  When `workerThreads` is `null`, the pool is sized to twice the available processor count.  If the pool is saturated the `CallerRunsPolicy` kicks in — the accepting thread runs the request directly, which throttles the accept rate naturally.

Set this explicitly when you know your handler concurrency requirements:

```json
{ "jdkRunConfig": { "reliability": { "workerThreads": 16 } } }
```

### `scheduleLockTtl` — Distributed Schedule Lock TTL

**Default: 1 hour**  
**Applies to: Ktor, Netty, JDK**

Lightning Server uses a distributed lock (backed by your configured cache) to ensure that only one server instance runs a given scheduled task tick when multiple instances are running behind a load balancer.  The lock is released immediately when the tick finishes, and on graceful shutdown.  This TTL is the backstop for a hard crash: if an instance crashes while holding the lock, other instances will not run that tick until the lock expires.

If a scheduled task runs longer than `scheduleLockTtl`, another instance may start the same tick concurrently once the lock expires.  Keep individual ticks shorter than this value, or raise it for long-running tasks:

```json
{ "reliability": { "scheduleLockTtl": "4h" } }
```

## Per-Request Timeout

Per-request timeouts are **not** part of `EngineReliabilitySettings`.  Each `HttpHandler` carries its own `timeout` property (default 30 seconds).  The framework enforces it centrally in `ServerRuntime.handle`, so the limit is identical across all engines.

To override the default for a specific handler:

```kotlin
// Illustrative.
val slowReport = path.path("report").get bind HttpHandler(
    timeout = 5.minutes
) {
    generateHeavyReport()
}
```

Set the handler timeout based on what your handler actually needs, rather than raising `shutdownDrainTimeout` globally.

## Netty-Specific Settings

`NettyRuntimeSettings` exposes additional Netty tuning beyond the shared reliability object:

| Field | Default | Purpose |
|---|---|---|
| `workerThreads` | `null` (Netty default: 2× CPUs) | Worker event-loop thread count. |
| `maxAggregatedContentLength` | `16 MiB` | Netty's HTTP aggregator body cap (instead of `reliability.maxBodySize`). |
| `websocketCompression` | `false` | Enable per-message deflate WebSocket compression. |
| `backlog` | `4096` | TCP `SO_BACKLOG` — accept queue depth. |
| `recvBufBytes` | `null` (system default) | TCP `SO_RCVBUF` receive buffer size. |
| `sendBufBytes` | `null` (system default) | TCP `SO_SNDBUF` send buffer size. |
| `autoRead` | `true` | Set to `false` to enable manual flow control via Netty. |

```json
{
  "nettyRunConfig": {
    "host": "0.0.0.0",
    "port": 8080,
    "maxAggregatedContentLength": "32MiB",
    "websocketCompression": true,
    "workerThreads": 8,
    "reliability": {
      "idleTimeout": "60s",
      "shutdownDrainTimeout": "20s"
    }
  }
}
```

> `reliability.maxBodySize` is **ignored** by `NettyEngine` — use `maxAggregatedContentLength` for the body cap.

## Which Knob Applies to Which Engine

| Knob | Ktor | Netty | JDK |
|---|---|---|---|
| `maxBodySize` (reliability) | Yes | **No** — use `maxAggregatedContentLength` | Yes |
| `idleTimeout` (reliability) | No | Yes | No |
| `shutdownDrainTimeout` (reliability) | Yes | Yes | Yes |
| `webSocketInboundBuffer` (reliability) | Yes | Yes | No (no WS) |
| `webSocketOversizePolicy` (reliability) | Yes | Yes | No (no WS) |
| `workerThreads` (reliability) | No | No | Yes |
| `scheduleLockTtl` (reliability) | Yes | Yes | Yes |
| `workerThreads` (Netty-level) | — | Yes | — |
| `maxAggregatedContentLength` (Netty-level) | — | Yes | — |
| Per-handler `HttpHandler.timeout` | Yes | Yes | Yes |

## What's Next

- **Running your server** — the `start()` pattern, first-run `settings.json` generation, and engine comparison table: [Running Your Server](../guide/running.md).
- **Observability** — pairing engine metrics (latency, error rate) with distributed tracing: [Observability](observability.md).
- **AWS serverless** — the `engine-aws-serverless` module generates Terraform for Lambda + API Gateway deployment; reliability knobs do not apply there — Lambda handles scaling and timeouts at the platform level.
