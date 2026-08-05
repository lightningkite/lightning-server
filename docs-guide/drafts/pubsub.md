> # ⚠️ UNREVIEWED FIRST DRAFT — DO NOT PUBLISH
> Auto-generated first pass. Code samples are modeled on the current source but have **NOT** been compiled, drift-checked, or reviewed. This page is intentionally **not** in the site nav. Before publishing: port samples into the drift-checked `docs-guide/src/samples` module, run the fresh-eyes judge, and delete this banner.

# PubSub

PubSub is a publish/subscribe messaging service.  Any code with access to a
channel can publish a typed value to it; every active subscriber receives that
value.  The key use case in Lightning Server is **real-time fan-out across
instances**: when one server instance writes to the database, it publishes an
event so every other instance can push updates to connected WebSocket clients
without polling.

PubSub is a **fire-and-forget, at-most-once** transport.  Messages are not
queued or persisted.  A subscriber only receives messages published while it is
actively collecting.  If you need durable delivery, use a task queue or a
persistent store instead.

## Imports

All examples in this chapter use the following imports:

```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.services.pubsub.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import kotlin.uuid.*
```

`com.lightningkite.services.pubsub.*` brings in `PubSub`, `PubSubChannel`, and
the reified `PubSub.get<T>()` inline extension.

## Declaring the PubSub Setting

Declare `val pubsub = setting("pubSub", PubSub.Settings())` in your
`ServerBuilder`.  The default URL is `"local"` — an in-process, coroutine-backed
channel suitable for single-instance deployments, tests, and local development:

```kotlin
// Illustrative.
object NotificationServer : ServerBuilder() {
    val pubsub = setting("pubSub", PubSub.Settings())

    // Other settings, endpoints, etc.
}
```

The string `"pubSub"` becomes the key in `settings.json`.  To change the
backend, update `settings.json` — no Kotlin code changes required.

## Getting a Channel

Call `pubsub().get<T>("channel-key")` inside any handler (or any other place a
`ServerRuntime` is in context) to obtain a typed `PubSubChannel<T>`.  The key
is any string that names the logical channel; all parties using the same key
communicate on the same channel:

```kotlin
// Illustrative.
@Serializable
data class PostEvent(val postId: Uuid, val action: String)

// Inside a handler or coroutine with ServerRuntime in context:
val channel: PubSubChannel<PostEvent> = pubsub().get<PostEvent>("post-events")
```

The reified `get<T>()` extension uses the server's serializers module to pick
the right serializer for `T`.  If you need to supply a serializer explicitly:

```kotlin
// Illustrative.
val channel = pubsub().get("post-events", PostEvent.serializer())
```

For plain strings, use the `string()` shortcut (no serialization overhead):

```kotlin
// Illustrative.
val logChannel = pubsub().string("audit-log")
```

## Publishing

Call `channel.emit(value)` to publish a message.  `emit` is a suspending
function; it returns once the message has been handed to the backend:

```kotlin
// Illustrative — inside an HTTP handler.
val announce = path.path("posts").path("publish").post bind HttpHandler { request ->
    val post = /* ... parse body ... */
    postTable().insertOne(post)

    // Notify all subscribers that a new post is available.
    pubsub().get<PostEvent>("post-events").emit(PostEvent(postId = post._id, action = "created"))

    HttpResponse.plainText("Published")
}
```

`emit` does not wait for subscribers to process the message.  If no subscriber
is active at the moment of publish, the message is silently discarded.

## Subscribing

`PubSubChannel<T>` implements `Flow<T>`, so you subscribe by calling
`channel.collect { value -> ... }`.  `collect` suspends until the underlying
flow is cancelled:

```kotlin
// Illustrative — launch a background coroutine that reacts to events.
// This would typically be started inside a task or engine lifecycle hook,
// not inside an HTTP handler (which has a bounded lifetime).
launch {
    pubsub().get<PostEvent>("post-events").collect { event ->
        println("Received event: $event")
        // Forward to a WebSocket connection, update a cache, etc.
    }
}
```

> Because `collect` suspends indefinitely, start subscriptions in long-lived
> coroutine scopes (a task, a background job, or the engine's main coroutine)
> rather than inside an HTTP request handler whose coroutine scope ends when the
> response is sent.

## Real-Time Fan-Out Across Instances

The most common production use of PubSub is to keep WebSocket connections alive
across multiple server instances.  When one instance handles an HTTP write, it
publishes an event.  Every instance that has a WebSocket subscriber collects the
event and pushes it to connected clients.

Lightning Server's `WebSocketTopic` (documented in [WebSockets](../guide/websockets.md))
is the highest-level API for this pattern — it uses the engine's built-in pubsub
setting so you do not need to wire `PubSub` directly.  Use raw `PubSub` when:

- You want to fan out between endpoints that are not WebSocket handlers.
- You need a shared event bus between different parts of your server.
- You are integrating with an external system that publishes to Redis.

Example — two endpoints share a channel; one publishes, the other subscribes
via a long-running coroutine started at startup:

```kotlin
// Illustrative.
object FanOutServer : ServerBuilder() {
    val pubsub = setting("pubSub", PubSub.Settings())

    @Serializable
    data class Notification(val message: String)

    // POST /notify — publish a notification.
    val notify = path.path("notify").post bind HttpHandler { request ->
        val msg = request.body!!.text()
        pubsub().get<Notification>("notifications").emit(Notification(msg))
        HttpResponse.plainText("Sent")
    }

    // This coroutine would be started inside an engine lifecycle hook or task.
    // It fans out to however many consumers are registered in `consumers`.
    val consumers = mutableListOf<suspend (Notification) -> Unit>()

    suspend fun startFanOut() {
        pubsub().get<Notification>("notifications").collect { note ->
            consumers.forEach { it(note) }
        }
    }
}
```

For the typical "push database changes to WebSocket clients" pattern, combine
the table interceptors from [Advanced Database](advanced-database.md) with a
`PubSubChannel`:

```kotlin
// Illustrative.
// 1. After each insert, publish the new post to all subscribers.
val postsTable = postTable()
    .postCreate { post ->
        pubsub().get<Post>("new-posts").emit(post)
    }

// 2. In a WebSocket handler, subscribe a connection to new-posts.
//    (Using raw collect — see websockets.md for the higher-level topic API.)
val feed = path.path("feed") bind WebSocketHandler(
    storageSerializer = Unit.serializer(),
    willConnect = { Unit },
    didConnect = {
        // Launch a coroutine per connection that relays PubSub events to the client.
        launch {
            pubsub().get<Post>("new-posts").collect { post ->
                send(Json.encodeToString(post))
            }
        }
    },
    disconnect = { /* coroutine cancelled automatically */ }
)
```

## Backends

The backend is determined by the URL in `PubSub.Settings`.

### `local` (default)

```json
{ "pubSub": "local" }
```

In-process `MutableSharedFlow`.  Only works within a single JVM.  Safe for
tests and single-instance deployments.  Zero external dependencies.

### `debug`

```json
{ "pubSub": "debug" }
```

Same as `local` but prints every `emit` call to stdout.  Useful during
development to confirm messages are being published.

### Redis — `redis://` / `rediss://`

Requires the `pubsub-redis` module on the classpath.  Touch the companion
object at startup to register the URL scheme:

```kotlin
// In your ServerBuilder init block or main():
RedisPubSub  // references the companion object, triggering its init block
```

URL examples:

| URL | Meaning |
|---|---|
| `redis://localhost:6379` | Local Redis, no auth |
| `redis://user:password@redis.example.com:6379` | Authenticated Redis |
| `rediss://master.cache.amazonaws.com:6380` | TLS (ElastiCache, etc.) |

```json
{ "pubSub": "redis://localhost:6379" }
```

Redis PubSub delivers messages across every instance connected to the same
Redis server.  This is the standard choice for multi-instance deployments.

> **Redis delivery guarantees:** At-most-once.  Messages published while no
> subscriber is connected to that Redis channel are dropped.  Use Redis Streams
> (a separate service) if you need at-least-once delivery.

### AWS DynamoDB — `dynamodb-pubsub://`

Requires the `pubsub-aws` module.  Touch the companion object at startup:

```kotlin
DynamoDbPubSub
```

URL format:

```
dynamodb-pubsub://[accessKey:secretKey@]region/tableName[?pollInterval=ms]
```

```json
{ "pubSub": "dynamodb-pubsub://us-east-1/my-pubsub-table" }
```

DynamoDB-backed fan-out is suitable for AWS Lambda deployments where Redis is
unavailable.  It uses DynamoDB Streams or polling under the hood, so latency is
higher than Redis.

## Testing

In tests, the `"local"` backend (the default) is correct because all handlers
run in the same process.  Pass `pubSub set PubSub.Settings("local")` in the
`settings` lambda if your test server has a custom default:

```kotlin
// Illustrative.
fun pubSubTest() = NotificationServer.testBlocking(
    settings = { pubsub set PubSub.Settings("local") }
) {
    val received = mutableListOf<PostEvent>()

    // Start collecting before emitting, otherwise the message is missed.
    val job = launch {
        NotificationServer.pubsub().get<PostEvent>("post-events").collect { event ->
            received.add(event)
        }
    }

    // Give the collector coroutine a moment to start.
    yield()

    NotificationServer.pubsub().get<PostEvent>("post-events").emit(
        PostEvent(postId = Uuid.random(), action = "created")
    )

    // In the local backend, emit() returns after the shared flow delivers to
    // active collectors, so received should be populated after a yield.
    yield()
    check(received.size == 1) { "Expected 1 event, got ${received.size}" }

    job.cancel()
}
```

> **Timing note:** `LocalPubSub` is backed by `MutableSharedFlow(0)` — zero
> replay.  A subscriber must be collecting before `emit` is called or it misses
> the message.  In tests, start the `collect` coroutine and `yield()` before
> emitting.

## What's Next

- **WebSocket push** — the `WebSocketTopic` API builds on pubsub to give each
  path its own typed fan-out channel with automatic subscription management.
  See [WebSockets](../guide/websockets.md).
- **Advanced Database** — combine table interceptors with PubSub channels to
  broadcast every database change to interested subscribers.
  See [Advanced Database](advanced-database.md).
