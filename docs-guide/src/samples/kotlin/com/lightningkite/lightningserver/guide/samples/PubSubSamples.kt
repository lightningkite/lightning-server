package com.lightningkite.lightningserver.guide.samples

// region pubsub-imports
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
// endregion pubsub-imports

// region pubsub-server
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
// endregion pubsub-server

// region pubsub-test
fun orderPubSubTest() = OrderServer.testBlocking(settings = {}) {
    coroutineScope {
        // Subscribe before publishing: a message nobody is listening for is dropped.
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { nextShipment.test(null, Unit) }
        ship.test(null, OrderShipped("A-1"))
        assertEquals(OrderShipped("A-1"), waiting.await())
    }
}
// endregion pubsub-test

// region pubsub-engine
object SharedPubSubServer : ServerBuilder() {
    val pubSub = setting("pubSub", PubSub.Settings())

    init { enginePubSub bind pubSub }
}
// endregion pubsub-engine

fun sharedPubSubTest() = SharedPubSubServer.testBlocking(settings = {}) {
    assertSame(pubSub(), enginePubSub())
}
