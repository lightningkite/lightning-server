# Pub/Sub

`PubSub` broadcasts typed messages on named channels: every instance collecting a channel receives what any instance
emits on it.  Delivery is fire-and-forget: a message reaches only subscribers that are listening at that moment, and
nothing is stored or retried.  For work that must happen, use [Tasks](tasks.md) or the database.

Most apps use pub/sub indirectly, through [WebSocket topics](websockets.md); this chapter covers that wiring and direct
use.

<!-- sample: com/lightningkite/lightningserver/guide/samples/PubSubSamples.kt#pubsub-imports -->
```kotlin
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.engine.local.enginePubSub
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.pubsub.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
```

## Publishing and waiting

<!-- sample: com/lightningkite/lightningserver/guide/samples/PubSubSamples.kt#pubsub-server -->
```kotlin
@Serializable
data class OrderShipped(val orderId: String)

object OrderServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    val pubSub = setting("pubSub", PubSub.Settings())

    val ship = path.path("orders").path("ship").post bind ApiHttpHandler(
        summary = "Mark an order shipped",
        auth = noAuth,
        implementation = { event: OrderShipped -> pubSub().get<OrderShipped>("orders").emit(event) }
    )

    val nextShipment = path.path("orders").path("next-shipment").post bind ApiHttpHandler(
        summary = "Wait for the next shipped order",
        auth = noAuth,
        implementation = { _: Unit ->
            withTimeoutOrNull(20.seconds) { pubSub().get<OrderShipped>("orders").first() }
        }
    )
}
```

`pubSub().get<T>("orders")` returns a `PubSubChannel<T>`, which is both a `Flow<T>` (collect it to subscribe) and a
`FlowCollector<T>` (`emit` to publish).  Everyone using the same name shares the channel, so give each channel one
message type.  `pubSub().string(name)` is a channel of plain strings.

`nextShipment` is a long poll: it subscribes, waits for one message, and gives up after 20 seconds (inside the
handler's default 30-second timeout).  Requests end, so collect inside a request only with a timeout or `first()`.

## Testing

The default `local` backend works in tests.  Subscribe before publishing:

<!-- sample: com/lightningkite/lightningserver/guide/samples/PubSubSamples.kt#pubsub-test -->
```kotlin
fun orderPubSubTest() = OrderServer.testBlocking(settings = {}) {
    coroutineScope {
        // Subscribe before publishing: a message nobody is listening for is dropped.
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { nextShipment.test(null, Unit) }
        ship.test(null, OrderShipped("A-1"))
        assertEquals(OrderShipped("A-1"), waiting.await())
    }
}
```

`CoroutineStart.UNDISPATCHED` runs the waiting request until it suspends inside `first()`, so it is subscribed by the
time `ship` emits.

## WebSocket topics across instances

[WebSocket topics](websockets.md) deliver through the engine's own pub/sub, the `engine-pubSub` setting (default
`local`).  With more than one instance, set it to a shared backend, or a message sent on one instance never reaches
sockets connected to another.  To configure one pub/sub for both your code and the engine, bind the engine's setting
to yours; `engine-pubSub` then leaves `settings.json`:

<!-- sample: com/lightningkite/lightningserver/guide/samples/PubSubSamples.kt#pubsub-engine -->
```kotlin
object SharedPubSubServer : ServerBuilder() {
    val pubSub = setting("pubSub", PubSub.Settings())

    init { enginePubSub bind pubSub }
}
```

## Backends

```json
{ "pubSub": "redis://cache.internal:6379" }
```

| URL | Module | Register with |
|---|---|---|
| `local` (default) | built in | |
| `debug` (`local`, logging each message) | built in | |
| `redis://[:password@]host:port[/db]` | `pubsub-redis` | `RedisPubSub` |
| `dynamodb-pubsub://[accessKey:secretKey@]region/table[?pollInterval=<ms>]` | `pubsub-aws` | `DynamoDbPubSub` |

Reference the class in your server's `init {}` so its scheme is registered (see
[Services](services.md#registering-a-backend)).  `local` and `debug` reach only the same process.  Redis delivers to
every instance connected to the same server.  `dynamodb-pubsub` polls a table (every 15 ms by default), for AWS
deployments without Redis.  Full URL syntax:
[PubSub URLs](https://github.com/lightningkite/service-abstractions/blob/main/docs/settings-url-reference.md#pubsub).

## Gotchas

- At most once: a message emitted while nobody is collecting is gone, and a subscriber that starts later doesn't see
  earlier messages.
- `local` buffers 64 messages per channel for slow subscribers, then drops the oldest.
- Don't start a server-lifetime subscriber in a startup task: engines wait for startup tasks to finish before
  serving, and each has a timeout.  Subscribe within the work that needs the message, as `nextShipment` does.

## Next

- [WebSockets](websockets.md): topics, the usual way to push to clients.
- [Model Realtime](model-realtime.md): streaming database changes to clients.
- [Caching](caching.md): shared state that persists between requests.
