package com.lightningkite.lightningserver.engine.ktor

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.definition.loggingSettings
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.engine.local.engineCache
import com.lightningkite.lightningserver.engine.local.enginePubSub
import com.lightningkite.lightningserver.engine.local.forceWebSocketPubSub
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.websockets.WebSocketClose
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.webSocketSettings
import io.ktor.client.plugins.websocket.*
import io.ktor.server.testing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.builtins.serializer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The counterpart to [WebSocketShutdownDisconnectTest]: an ordinary client-initiated close must
 * report [WebSocketClose.NORMAL], exactly once.
 *
 * Ktor's ordinary close leaves the socket loop normally rather than by exception, so it is the half
 * of the disconnect path that the cancellation work did *not* touch — and the half nothing covered.
 * Netty's equivalent turned out to deliver this disconnect twice; without this, the same regression
 * on the Ktor side would go unnoticed.
 */
class WebSocketClientCloseTest {

    object TestServer : ServerBuilder() {
        val chat = path.path("chat") bind WebSocketHandler(
            storageSerializer = Unit.serializer(),
            willConnect = { Unit },
            didConnect = { serverConnected.complete(Unit) },
            disconnect = { reason ->
                reasons.add(reason)
                serverDisconnected.complete(Unit)
            },
        )
    }

    companion object {
        val serverConnected = CompletableDeferred<Unit>()
        val serverDisconnected = CompletableDeferred<Unit>()
        val reasons = CopyOnWriteArrayList<WebSocketClose>()
    }

    @Test
    fun client_close_reports_normal_exactly_once() {
        val engine = KtorEngine(TestServer.build())
        engine.settings.run {
            generalSettings.useDefault()
            secretBasis.useDefault()
            loggingSettings.useDefault()
            enginePubSub.useDefault()
            engineCache.useDefault()
            webSocketSettings.useDefault()
            forceWebSocketPubSub set true  // the disconnect handling under test lives in the pub/sub branch
            ktorRunConfig set KtorRuntimeSettings(host = "127.0.0.1", port = 0)
        }
        engine.settings.readyUsingDefaults()

        testApplication {
            application { with(engine) { adapt() } }
            val client = createClient { install(WebSockets) }
            // Leaving the block closes the socket from the client side, which is the path under test.
            client.webSocket("/chat?path=/chat") { serverConnected.await() }
            serverDisconnected.await()
        }

        assertEquals(listOf(WebSocketClose.NORMAL), reasons.toList())
    }
}
