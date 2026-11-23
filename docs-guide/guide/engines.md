# Engines

An engine runs a `ServerDefinition` on a port.  There are three standalone engines, all built on `LocalEngine`
and all production-ready; they share one set of reliability settings that guard against slow clients, oversized
bodies, unbounded WebSocket buffering, and abrupt restarts.  AWS Lambda uses `AwsAdapter` instead (see
[AWS deployment](aws-deployment.md)); none of this chapter applies there.

## Choosing an engine

| Engine | Module | Start | WebSockets | Notes |
|---|---|---|---|---|
| `KtorEngine` | `engine-ktor` | `start(Netty)` | Yes | The default choice.  Ktor over Netty. |
| `NettyEngine` | `engine-netty` | `start()` | Yes | Direct Netty; exposes TCP and aggregator tuning. |
| `JdkEngine` | `engine-jdk-server` | `start()` | No: upgrades get `501` | No HTTP library; the JDK's built-in `HttpServer`. |

`KtorEngine` is shown in [Running](running.md).  The others start the same way:

<!-- sample: com/lightningkite/lightningserver/guide/samples/EnginesSamples.kt#serve-netty -->
```kotlin
fun serveNetty() {
    NettyEngine(HelloServer.build()).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        start()
    }
}
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/EnginesSamples.kt#serve-jdk -->
```kotlin
fun serveJdk() {
    JdkEngine(HelloServer.build()).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        start()
    }
}
```

Imports: `com.lightningkite.lightningserver.engine.netty.NettyEngine` and
`com.lightningkite.lightningserver.engine.jdk.JdkEngine`.

## Engine settings

Each engine adds its own key to `settings.json`:

| Engine | Key | Class |
|---|---|---|
| `KtorEngine` | `ktorRunConfig` | `KtorRuntimeSettings` |
| `NettyEngine` | `nettyRunConfig` | `NettyRuntimeSettings` |
| `JdkEngine` | `jdkRunConfig` | `JdkRuntimeSettings` |

```json
{
  "ktorRunConfig": {
    "host": "0.0.0.0",
    "port": 8080,
    "realIpHeader": "X-Forwarded-For",
    "reliability": {
      "maxBodySize": 67108864,
      "shutdownDrainTimeout": "PT20S",
      "webSocketOversizePolicy": "SUSPEND",
      "scheduleLockTtl": "PT4H"
    }
  }
}
```

Omitted fields keep their defaults.  Sizes are byte counts; durations are ISO-8601 (`PT25S`, `PT2M`, `PT1H`).

| Field | Default | Meaning |
|---|---|---|
| `host` | `"0.0.0.0"` | Bind address |
| `port` | `8080` | Listen port |
| `realIpHeader` | `null` | Header holding the client IP, set by your proxy (`X-Forwarded-For`, `X-Real-IP`) |
| `requestIdHeader` | `null` | Header holding a request ID stamped by a trusted proxy; must be a version 7 UUID |
| `reliability` | see below | `EngineReliabilitySettings` |

The engine takes the right-most comma-separated entry of `realIpHeader`: the address your proxy appended, so a
client can't spoof it by sending its own `X-Forwarded-For`.  Set `realIpHeader` and `requestIdHeader` only behind a
proxy; without one, any client can choose its own IP (and dodge IP-based rate limits) or request ID.  With `requestIdHeader` null, the engine always
generates its own ID.  Envoy's built-in `X-Request-ID` is a version 4 UUID, so it doesn't qualify.

## Reliability settings

| Field | Default | Ktor | Netty | JDK | Meaning |
|---|---|---|---|---|---|
| `maxBodySize` | 16 MiB (`16777216`) | Yes | No | Yes | Larger bodies get `413`, checked against `Content-Length` and while streaming |
| `idleTimeout` | `PT2M` | No | Yes | No | Close keep-alive connections idle this long |
| `shutdownDrainTimeout` | `PT25S` | Yes | Yes | Yes | How long shutdown waits for in-flight requests |
| `webSocketInboundBuffer` | `256` | Yes | Yes | n/a | Frames buffered between the socket and your handler |
| `webSocketOversizePolicy` | `CLOSE` | Yes | Yes | n/a | What happens when that buffer is full (below) |
| `workerThreads` | `null` (CPUs × 2) | No | No | Yes | Request thread pool size |
| `scheduleLockTtl` | `PT1H` | Yes | Yes | Yes | Expiry of the lock that keeps one instance per schedule tick |

| `webSocketOversizePolicy` | Effect |
|---|---|
| `CLOSE` | Close the socket with code 1009 (message too big) |
| `DROP_OLDEST` | Drop the oldest buffered frame; lossy, keeps the socket open |
| `SUSPEND` | Stop reading the socket until the handler catches up: TCP backpressure on the peer |

**Shutdown.**  On `SIGTERM` or `SIGINT` the engine stops accepting connections, cancels schedules, waits up to
`shutdownDrainTimeout`, and disconnects services.  Keep it below your orchestrator's grace period (Kubernetes
defaults to 30 seconds), or the process is killed mid-drain.

**Schedule locks.**  Before running a tick, an instance takes a lock in the engine cache for `scheduleLockTtl`, and
releases it when the tick ends or on graceful shutdown.  The TTL only matters after a crash.  A tick that runs
longer than the TTL can start again on another instance, so keep ticks shorter or raise it.

## Netty settings

`NettyRuntimeSettings` adds:

| Field | Default | Meaning |
|---|---|---|
| `workerThreads` | `null` (Netty chooses, about CPUs × 2) | Event-loop threads |
| `maxAggregatedContentLength` | 16 MiB (`16777216`) | The body size cap.  Netty uses this, not `reliability.maxBodySize`.  Max ~2 GiB |
| `webSocketCompression` | `false` | Per-message deflate |
| `backlog` | `4096` | TCP accept queue (`SO_BACKLOG`) |
| `recvBufBytes` / `sendBufBytes` | `null` (OS default) | `SO_RCVBUF` / `SO_SNDBUF` |
| `autoRead` | `true` | Netty's automatic channel reads |

```json
{
  "nettyRunConfig": {
    "port": 8080,
    "workerThreads": 8,
    "maxAggregatedContentLength": 33554432,
    "webSocketCompression": true,
    "reliability": {
      "idleTimeout": "PT1M",
      "shutdownDrainTimeout": "PT20S"
    }
  }
}
```

Netty uses epoll (Linux) or kqueue (macOS) when the native transport library for the platform is on the classpath,
and NIO otherwise.  `engine-netty` ships only the transport classes, so add Netty's native artifact
(`netty-transport-native-epoll`, with the platform classifier) to get epoll.

## Shared engine settings

All three engines also add these keys:

| Key | Default | Meaning |
|---|---|---|
| `engine-cache` | `"ram"` | Cache for schedule locks |
| `engine-pubSub` | `"local"` | Pub/sub for WebSocket messages between instances |
| `forceWebSocketPubSub` | `false` | Route WebSocket handlers through pub/sub even when they could run directly, to test the multi-instance path locally |
| `webSocketSettings` | `ping` `PT30S`, `pongTimeout` `PT15S`, `maxFrameSize` 16 MiB | WebSocket transport limits |

With more than one instance, point `engine-cache` and `engine-pubSub` at shared backends (Redis, for example).  On
the in-process defaults each instance has its own locks, so every instance runs every schedule tick, and a message
published on one instance never reaches sockets on another.

In `webSocketSettings`, `ping` and `pongTimeout` are applied by `KtorEngine` and `NettyEngine`; they close a
connection (1011, "Ping timeout") when a ping goes unanswered, which is how they notice a peer that has stopped reading.  `maxFrameSize` is applied by Ktor and
Netty.  Setting `ping` to `null` turns pings off.

## Per-request timeouts

Request timeouts belong to handlers, not engines, and every engine enforces them the same way.  `HttpHandler`
defaults to 30 seconds:

<!-- sample: com/lightningkite/lightningserver/guide/samples/EnginesSamples.kt#handler-timeout -->
```kotlin
object SlowReportServer : ServerBuilder() {
    val report = path.path("report").get bind HttpHandler(timeout = 5.minutes) {
        HttpResponse.plainText("done")
    }
}
```

Raise the timeout on the handlers that need it rather than raising `shutdownDrainTimeout` for the whole server.

## Gotchas

- **Other Ktor engines**: `engine-ktor` brings Ktor's Netty engine.  To use `start(CIO)` or Jetty, add that Ktor
  server artifact yourself.
- **Responses without `Content-Length`**: `KtorEngine` streams responses.  For clients that require a length
  (some IoT devices), pass `disableResponseStreaming = { request -> true }` to its constructor.
- **`JdkEngine` is thread-per-request**: concurrency is capped by `workerThreads`.  When the pool and its queue
  are full, the accepting thread runs the request itself, which slows accepting.

## Next

- [Running](running.md): `settings.json` on first run, serve / predeploy / dev
- [Observability](observability.md)
- [Deploying to a VM](deploy-vm.md), [Deploying to EC2](deploy-ec2.md)
