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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A socket that is still open when the server goes down is torn down by cancellation, not by an
 * error. This pins both halves of that: the handler's `disconnect` still runs (it is wrapped in
 * `NonCancellable`), and the reason it is given is [WebSocketClose.GOING_AWAY] rather than the
 * [WebSocketClose.INTERNAL_ERROR] that deriving the code from a 500 used to produce.
 */
class WebSocketShutdownDisconnectTest {

    object TestServer : ServerBuilder() {
        val hold = path.path("hold") bind WebSocketHandler(
            storageSerializer = Unit.serializer(),
            willConnect = { Unit },
            didConnect = { serverConnected.complete(Unit) },
            disconnect = { reason ->
                disconnectReason.set(reason)
                disconnected.countDown()
            },
        )
    }

    companion object {
        val serverConnected = CompletableDeferred<Unit>()
        val disconnectReason = AtomicReference<WebSocketClose?>(null)
        val disconnected = CountDownLatch(1)
    }

    @Test
    fun disconnect_on_shutdown_reports_going_away() {
        val engine = KtorEngine(TestServer.build())
        engine.settings.run {
            generalSettings.useDefault()
            secretBasis.useDefault()
            loggingSettings.useDefault()
            enginePubSub.useDefault()
            engineCache.useDefault()
            webSocketSettings.useDefault()
            forceWebSocketPubSub set true  // the cancellation handling under test lives in the pub/sub branch
            ktorRunConfig set KtorRuntimeSettings(host = "127.0.0.1", port = 0)
        }
        engine.settings.readyUsingDefaults()

        val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            testApplication {
                application { with(engine) { adapt() } }
                val client = createClient { install(WebSockets) }

                // Hold the socket open from the client so the test application's teardown finds it still
                // connected and cancels the server-side coroutine rather than closing it cleanly.
                clientScope.launch {
                    client.webSocket("/hold?path=/hold") { awaitCancellation() }
                }
                // Wait for the server side specifically: the client's handshake returns before the
                // engine's socket body has run, and a socket cancelled before `didConnect` would never
                // reach the disconnect path at all.
                serverConnected.await()
            }
        } finally {
            clientScope.cancel()
        }

        assertTrue(
            disconnected.await(5, TimeUnit.SECONDS),
            "disconnect phase never ran for a socket cancelled by shutdown",
        )
        assertEquals(WebSocketClose.GOING_AWAY, disconnectReason.get())
    }
}
