# Running Your Server

Every guide chapter so far has used `ServerBuilder.test()` to exercise
endpoints in-process without binding any port.  This chapter shows how to go
from that definition to a real HTTP server that accepts connections.

## How it Works: ServerBuilder → Engine

A `ServerBuilder` object describes your server — its endpoints, settings, and
registered services — but it does not bind any port.  To run it you:

1. Call `build()` to seal the definition into a `ServerDefinition`.
2. Wrap the definition in an **engine** that drives HTTP I/O.
3. Load `settings.json` into the engine's settings registry.
4. Call `start()` — which blocks until the process shuts down.

```
ServerBuilder → .build() → ServerDefinition → Engine → .start()
```

## Imports

All examples in this chapter use the following imports:

<!-- sample: com/lightningkite/lightningserver/guide/samples/RunningSamples.kt#running-imports -->
```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import kotlinx.coroutines.*
```

## Building the Server Definition

`ServerBuilder.build()` is a pure, blocking call that seals all endpoint and
service registries and returns a `ServerDefinition`.  It has no side effects
and requires no external services:

<!-- sample: com/lightningkite/lightningserver/guide/samples/RunningSamples.kt#build-server -->
```kotlin
// ServerBuilder.build() seals all registries, caches calculations, and returns
// a ServerDefinition — a pure description of your server with no external connections.
// Passing the result to an engine is what actually binds a port and starts accepting requests.
fun buildServer() {
    val built = RunningExampleServer.build()
    // `built` is a ServerDefinition: all endpoint registries are sealed and ready.
    // Hand it to an engine to run (see the illustrative main() example in the chapter).
    println("Server definition created with ${built.endpoints.entries.count()} endpoint entries")
}
```

## Choosing an Engine

Lightning Server ships three standalone engines:

| Engine | Import | When to use |
|---|---|---|
| `KtorEngine` | `engine-ktor` | Recommended for development and production. HTTP/1.1 + HTTP/2 + WebSockets. Backed by the Ktor framework. |
| `NettyEngine` | `engine-netty` | High-throughput production deployment. Uses native epoll (Linux) / kqueue (macOS) transport. |
| `JdkEngine` | `engine-jdk-server` | Zero external HTTP library dependency. **Does not support WebSockets.** |

There is also `LocalEngine` (`engine-local`), which is the in-process engine
used by `ServerBuilder.test()` — it is not for real network servers.

## A Real `main()` (Illustrative)

The following `main()` function is the pattern used by the demo server.  It
cannot run inside a unit test (it binds a port and blocks), so it is shown
here as illustrative rather than as a drift-checked sample region.

The `internalSerializersModule` property on the engine provides the
serializers module that `loadFromFile` needs to parse custom settings types.
It is a property of the engine (an `Engine`), not of `built` directly.

```kotlin
// Illustrative — verified against demo/src/main/kotlin/.../main.kt.
// Imports needed in real code:
//   import com.lightningkite.lightningserver.engine.ktor.KtorEngine
//   import com.lightningkite.lightningserver.settings.loadFromFile
//   import com.lightningkite.services.kfile.KFile
//   import io.ktor.server.netty.Netty

fun main() {
    val built = Server.build()
    KtorEngine(built).apply {
        // loadFromFile reads settings.json and populates all declared ServerSetting values.
        // internalSerializersModule is on the engine (an Engine) — it supplies the
        // serializers needed to parse custom setting types registered by your ServerBuilder.
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        // start(Netty) blocks until the process is stopped.
        // Other Ktor engine factories: CIO, Jetty.
        start(Netty)
    }
}
```

For Netty or JDK engines the pattern is identical, with `.start()` taking no
argument (those engines are self-contained, not factory-based):

```kotlin
// Illustrative.
fun mainNetty() {
    val built = Server.build()
    NettyEngine(built).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        start() // no factory argument
    }
}

fun mainJdk() {
    val built = Server.build()
    JdkEngine(built).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        start() // no factory argument; note: WebSockets are NOT supported
    }
}
```

## The `settings.json` First-Run Behavior

`loadFromFile` reads `settings.json` from the path you specify.  On first run
the file does not exist:

1. Lightning Server **writes a generated `settings.json`** containing default
   values for every declared `setting(key, default)`.
2. It then throws `MissingSettingFile` (which exits the process with a
   stack trace).
3. On the **second run**, the file exists, is parsed, and the server starts
   normally.

This two-run startup is intentional and expected.  The generated file is a
starting point — edit it to point at real backends (MongoDB, Redis, S3, etc.)
before running in production.

If `settings.json` exists but a required key is absent, Lightning Server
writes a `settings.suggested.json` next to it with the full set of keys
filled in, then throws `IncompleteSettingsException`.  Copy the relevant
lines from the suggested file into `settings.json` and restart.

## Graceful Shutdown

All three engines register a JVM shutdown hook on `start()`.  Sending
`SIGTERM` (or pressing Ctrl-C for `SIGINT`) triggers a graceful drain: the
engine stops accepting new connections, waits for in-flight requests to
complete (bounded by `shutdownDrainTimeout` in the engine's reliability
settings, default 30 seconds), disconnects services, and exits cleanly.

No explicit shutdown code is needed in your `main()`.

## Engine Configuration

Each engine reads its own settings key from `settings.json`.  The
relevant settings classes and their defaults:

| Engine | Settings class | Default key in JSON |
|---|---|---|
| `KtorEngine` | `KtorRuntimeSettings` | `"ktorRunConfig"` |
| `NettyEngine` | `NettyRuntimeSettings` | `"nettyRunConfig"` |
| `JdkEngine` | `JdkRuntimeSettings` | `"jdkRunConfig"` |

All three share these core fields (from `EngineReliabilitySettings`):

- **`host`** — bind address (default `"0.0.0.0"`)
- **`port`** — listen port (default `8080`)
- **`realIpHeader`** — header to read the client's real IP from when behind a
  proxy (e.g. `"X-Forwarded-For"`)
- **`reliability.shutdownDrainTimeout`** — how long to wait for in-flight
  requests before forcing close (default 30 seconds)
- **`reliability.maxBodySize`** — maximum accepted request body size

The Netty engine additionally respects `reliability.idleTimeout` and
`reliability.workerThreads`; the JDK engine respects `reliability.workerThreads`.
The Ktor engine ignores both of those (Ktor manages its own thread pool).

> The engine classes and `KFile`/`loadFromFile`/`start()` signatures above are
> verified against the engine source files in `engine-ktor/src/main/kotlin/`,
> `engine-netty/src/main/kotlin/`, and `engine-jdk-server/src/main/kotlin/`,
> and against `demo/src/main/kotlin/.../main.kt`.  The `main()` functions are
> illustrative because starting a real server cannot run inside a unit test.
