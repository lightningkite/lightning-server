# Realtime model updates

`ModelRestUpdatesWebSocket` adds a live query to a model: a client sends a `Condition<T>` over a websocket and is
pushed every change to rows that match it.  It uses the model's `ModelInfo`, so callers only ever receive rows and
fields their [permissions](permissions.md) allow.

## Minimal example

<!-- sample: com/lightningkite/lightningserver/guide/samples/ModelRealtimeSamples.kt#rt-imports -->
```kotlin
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.typed.ModelRestEndpointsAndUpdatesWebSocket.Companion.plus
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import kotlinx.serialization.builtins.serializer
import kotlin.uuid.*
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/ModelRealtimeSamples.kt#rt-server -->
```kotlin
@Queryable
data class Ticket(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val assignee: String,
    val open: Boolean = true,
) : HasId<Uuid>

object TicketServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    val database = setting("database", Database.Settings())

    val tickets = database.modelInfo<HasId<*>?, Ticket, Uuid>(
        auth = noAuth,
        tableName = "Ticket",
        permissions = { ModelPermissions(read = Ticket.open eq true, manage = Condition.Always) },
    )

    val rest = path.path("tickets") include
        ModelRestEndpoints(tickets) + ModelRestUpdatesWebSocket(tickets, key = Ticket.assignee)
}
```

`ModelRestEndpoints(info) + ModelRestUpdatesWebSocket(info)` (the `plus` import is required) mounts both at the same
path: the REST routes under `/tickets`, and the websocket at `/tickets` itself.  The result exposes them as
`rest.endpoints` and `rest.webSocket`.  `ModelRestEndpointsAndUpdatesWebSocket(info, key)` builds the same pair in
one call.  To mount only the websocket, `include ModelRestUpdatesWebSocket(info)` on its own path.

## The protocol

<!-- sample: com/lightningkite/lightningserver/guide/samples/ModelRealtimeSamples.kt#rt-test -->
```kotlin
fun ticketUpdatesTest() = TicketServer.testBlocking(settings = { database set Database.Settings("ram") }) {
    val json = engine.externalSerialization.json
    val updatesSerializer = CollectionUpdates.serializer(Ticket.serializer(), Uuid.serializer())
    val received = mutableListOf<CollectionUpdates<Ticket, Uuid>>()

    val socket = TicketServer.rest.webSocket.webSocket.test()
    socket.onMessageSent = { frame -> received += json.decodeFromString(updatesSerializer, frame.text) }

    val watch: Condition<Ticket> = Ticket.assignee eq "alice"
    socket.send(WebSocketFrame.Text(json.encodeToString(Condition.serializer(Ticket.serializer()), watch)))

    val rest = TicketServer.rest.endpoints
    val jam = rest.insert.test(null, Ticket(title = "Printer jam", assignee = "alice"))
    rest.insert.test(null, Ticket(title = "VPN down", assignee = "bob"))
    rest.modify.test(jam._id, null, Modification { it.open assign false })

    check(received.size == 3)
    check(received[0].condition == watch)              // subscription acknowledged
    check(received[1].updates == setOf(jam))           // alice's new ticket; bob's is never sent
    check(received[2].remove == setOf(jam._id))        // closed, so no longer readable
}
```

The client sends a `Condition<T>` as JSON; sending another replaces the subscription.  The server answers each
subscription with `CollectionUpdates(condition = ...)` and then sends a `CollectionUpdates<T, ID>` for every write
that touches a matching row:

| Field | Meaning |
|---|---|
| `updates` | rows inserted or changed that match the condition (masked per the caller's read mask) |
| `remove` | ids of rows deleted, or changed so they no longer match or are no longer readable |
| `overload` | `true` instead of the data when a batch would exceed about 24 KB; re-run the query over HTTP |
| `condition` | set only on the acknowledgement |

The websocket sends no initial snapshot.  The usual client flow is: subscribe, fetch the current rows with the
REST `query` endpoint, then apply `updates` and `remove` as they arrive.  Generated SDKs expose the socket as
`updates()` on the model's client interface (`ClientModelRestUpdatesWebSocket`); see [Client SDKs](client-sdks.md).

## What triggers an update

Writes through `info.table()` or `info.table(auth)`, which includes every generated REST endpoint and your own
handlers that use them.  Writes through the raw `registerTable` accessor, `info.baseTable()`, or
`info.dangerouslyDirectTable()` are not broadcast.  To run your own code on the same changes, use
`info.registerChangeListener { changes -> }` or [signals](advanced-database.md#signals-and-interceptors).

## Permissions

A connection resolves the caller's permissions and mask when it subscribes, and re-resolves them on the first push
after `permissionRevalidation` (default 5 minutes) has passed:

```kotlin
ModelRestUpdatesWebSocket(info, permissionRevalidation = 1.minutes)
```

So a revoked permission stops reaching a socket within that window.  If the caller's authentication no longer
resolves at revalidation, or its token has expired, the server closes the socket with code `VIOLATED_POLICY`.

## Scaling with `key`

Without a key, every change is published on one topic, and every connection checks it against its condition.
With `key = Ticket.assignee`, changes are also published per value of that field, and a subscription whose
condition pins the field with `eq` or `inside` (as `Ticket.assignee eq "alice"` does) listens only to those values.
Choose the field your clients almost always filter by: an owner, an organization, a conversation.  Subscriptions
that don't constrain the key still work; they listen to everything.

## Next

- [Model REST endpoints](model-rest.md): the HTTP half.
- [WebSockets](websockets.md): hand-written websocket handlers and topics.
- [Pub/sub](pubsub.md): messaging between server instances.
