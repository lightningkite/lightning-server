> # ⚠️ UNREVIEWED FIRST DRAFT — DO NOT PUBLISH
> Auto-generated first pass. Code samples are modeled on the current source but have **NOT** been compiled, drift-checked, or reviewed. This page is intentionally **not** in the site nav. Before publishing: port samples into the drift-checked `docs-guide/src/samples` module, run the fresh-eyes judge, and delete this banner.

# Realtime Model Sync

`ModelRestUpdatesWebsocket` adds a live-query WebSocket to a model.  Clients
subscribe by sending a `Condition<T>` and receive `CollectionUpdates<T, ID>`
messages whenever documents matching that condition are inserted, updated, or
deleted.  The server enforces the same `ModelInfo` permissions as the REST
endpoints — only items the caller can read are ever sent.

This page assumes you have already read
[Model REST Endpoints](model-rest.md) and are familiar with `ModelInfo`,
`ModelPermissions`, and the `condition { }` DSL.

## Imports

```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.typed.ModelRestEndpointsAndUpdatesWebsocket.Companion.plus
import com.lightningkite.services.database.*
import kotlinx.serialization.*
import kotlin.uuid.*
```

## Adding a WebSocket to an Existing ModelInfo

Given any `ModelInfo<USER, T, ID>`, construct a `ModelRestUpdatesWebsocket` and
mount it alongside the REST endpoints.  The idiomatic pattern uses the `+`
operator extension provided by `ModelRestEndpointsAndUpdatesWebsocket.Companion`:

```kotlin
object BlogServer : ServerBuilder() {
    val database = setting("database", Database.Settings())

    val blogInfo = database.modelInfo(
        auth = UserAuth.require(),
        permissions = {
            if (auth.fetch().isSuperUser)
                ModelPermissions.allowAll<BlogPost>()
            else
                ModelPermissions(read = Condition.Always, manage = Condition.Never)
        },
    )

    // The + operator creates a ModelRestEndpointsAndUpdatesWebsocket that
    // includes both the REST endpoints and the WebSocket at the same path prefix.
    val blog = path.path("blog").path("rest") module (
        ModelRestEndpoints(blogInfo) + ModelRestUpdatesWebsocket(blogInfo)
    )
}
```

This mounts the WebSocket at the same path prefix as the REST endpoints.  If
you mount at `/blog/rest`, the WebSocket is at `ws:///blog/rest` (the root of
the module path).

### Alternatively: `ModelRestEndpointsAndUpdatesWebsocket`

The combined class constructor is equivalent and slightly more concise:

```kotlin
val blog = path.path("blog").path("rest") module ModelRestEndpointsAndUpdatesWebsocket(blogInfo)
```

Both forms produce identical routes and SDK output.  The `+` operator is
preferred when you need to hold a reference to the individual `ModelRestEndpoints`
or `ModelRestUpdatesWebsocket` instances.

### WebSocket-Only (without REST)

You can also mount just the WebSocket without the REST endpoints, e.g. to keep
read-only realtime access at a separate path from write endpoints:

```kotlin
val liveUpdates = path.path("blog").path("updates") module ModelRestUpdatesWebsocket(blogInfo)
```

## The Wire Protocol

The WebSocket exchanges two message types:

### Client → Server: `Condition<T>`

After connecting, the client sends a serialised `Condition<T>` to subscribe to
a live query.  The server replies immediately with a `CollectionUpdates<T, ID>`
that has `condition` set to the acknowledged condition (and empty `updates` /
`remove`) — this is the "subscription acknowledgement" frame.

The client may send a new `Condition<T>` at any time to change its subscription.
The server swaps the active filter immediately; subsequent delta messages reflect
the new condition.

### Server → Client: `CollectionUpdates<T, ID>`

Every push from the server is a `CollectionUpdates<T, ID>`:

```kotlin
// From service-abstractions/database-shared/.../CollectionUpdates.kt
data class CollectionUpdates<T : HasId<ID>, ID : Comparable<ID>>(
    val updates: Set<T>    = setOf(),  // inserted or updated items that match the condition
    val remove:  Set<ID>   = setOf(),  // IDs of items that no longer match (deleted or filtered out)
    val overload: Boolean  = false,    // true when the delta is too large; client should re-query
    val condition: Condition<T>? = null, // non-null only in the acknowledgement frame
)
```

A typical delta message has non-empty `updates` or `remove` and `overload =
false`.  When a batch of changes would produce a JSON payload larger than 24 KB,
the server sends `CollectionUpdates(overload = true)` instead; clients should
treat this as a signal to re-fetch the full list from the REST `query` endpoint.

### Permissions Filtering

Before sending any item, the server applies the `ModelInfo` permission mask and
condition:

- Items that do not satisfy the user's `read` condition are silently dropped.
- Items that pass the `read` condition but have a `readMask` applied are sent
  with masked fields.

This means two clients with different permissions on the same model see
different delta streams from the same underlying database changes — the server
handles the per-connection filtering automatically.

## The `key` Parameter (Hash-Partitioned Topics)

By default, every change to any document in the collection is broadcast on a
single **general topic** and filtered per-connection.  For high-write
collections this can be expensive: every connected client is woken up to check
whether a change concerns them.

Pass a `key: SerializableProperty<T, *>` to `ModelRestUpdatesWebsocket` to
enable **hash-partitioned topics**.  When a key is provided, changes are bucketed
by the hash of the key field, and a client's subscription is mapped to the
bucket(s) whose conditions include specific key values.

```kotlin
// Illustrative — hash-partition on the authorId field so that
// each author's changes are isolated to a small set of topic buckets.
val liveUpdates = ModelRestUpdatesWebsocket(
    info = blogInfo,
    key = BlogPost.path.authorId,   // SerializableProperty<BlogPost, Uuid>
)
```

When the client subscribes with a condition that has an equality or `inside`
check on `authorId` (e.g. `condition { it.authorId eq currentUserId }`), the
server maps the subscription to the specific hash bucket for that author rather
than the general broadcast topic.  Unrelated authors' changes never wake the
connection.

> The `key` optimisation is transparent to clients — the wire protocol is
> identical.  It matters only for server-side scalability.

## Accessing the WebSocket Endpoint Programmatically

`ModelRestUpdatesWebsocket` exposes the WebSocket handler and its backing topics
as named properties:

```kotlin
val ws: ModelRestUpdatesWebsocket<USER, T, ID> = ModelRestUpdatesWebsocket(info)

// The WebSocket ApiWebsocketHandler (for reference from tests or SDK metadata)
ws.websocket

// The broadcast topic — used internally; you can also publish to it directly
// from server-side code to push synthetic changes.
ws.generalTopic   // WebSocketTopic<PathSpec0, CollectionChanges<T>>

// The hash-partitioned topic (only meaningful when key != null)
ws.hashTopic      // WebSocketTopic<PathSpec1<Int>, CollectionChanges<T>>
```

Publishing to `generalTopic` or `hashTopic` directly (via `topic.send(value)`)
triggers the same per-connection filtering pipeline as a real database mutation.
This lets you push custom or synthetic events without modifying the database —
useful for test helpers or derived-state notifications.

## Typical Client Usage

The following is an **illustrative** sketch of how a generated Kotlin/Multiplatform
SDK client (from `FetcherSdk`) would use the WebSocket:

```kotlin
// Illustrative — generated SDK usage, not drift-checked.
val client = BlogRestEndpointsAndUpdatesWebsocket(httpFetcher, wsFetcher)

// Open the WebSocket connection.
val socket = client.websocket.connect()

// Send a subscription condition: "give me all published posts".
socket.send(condition<BlogPost> { it.status eq PostStatus.PUBLISHED })

// The server sends the current state in the condition-ack frame plus subsequent deltas.
socket.incoming.collect { updates: CollectionUpdates<BlogPost, Uuid> ->
    when {
        updates.overload -> {
            // Delta was too large; re-fetch from REST.
            val allPosts = client.rest.query(Query(condition<BlogPost> { it.status eq PostStatus.PUBLISHED }))
            localCache.reset(allPosts)
        }
        else -> {
            localCache.applyDelta(add = updates.updates, remove = updates.remove)
        }
    }
}
```

> This block is illustrative — the exact SDK class names, method names, and
> `Fetcher` types depend on your generated SDK output and are not drift-checked
> here.

## Demo Reference

`demo/…/BlogEndpoints.kt` is the canonical wiring example:

```kotlin
// From demo/src/main/kotlin/…/BlogEndpoints.kt
object BlogEndpoints : ServerBuilder() {
    val info = Server.database.modelInfo(
        auth = Server.UserAuth.require(),
        permissions = {
            if (auth.fetch().isSuperUser)
                ModelPermissions.allowAll<BlogPost>()
            else
                ModelPermissions(read = Condition.Always, manage = Condition.Never)
        },
    )
    val rest = path.path("rest") include ModelRestEndpoints(info) + ModelRestUpdatesWebsocket(info)
}
```

The demo mounts `BlogEndpoints` at `/blog` from `Server.kt`:

```kotlin
val blog = path.path("blog") module BlogEndpoints
```

This produces the WebSocket at `ws:///blog/rest`.

## What's Next

- **Model REST Endpoints** — the HTTP side of auto-CRUD:
  [Model REST Endpoints](model-rest.md).
- **WebSockets** — lower-level WebSocket primitives, topics, and connection
  state: [WebSockets](../guide/websockets.md).
- **Typed Endpoints & SDK Generation** — how both REST and WebSocket endpoints
  participate in SDK generation:
  [Typed Endpoints, Errors & SDK Generation](../guide/typed-endpoints.md).
