package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.services.pubsub.PubSub
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class CoroutineWebsocketHandlerSimpleTest {

    object TestServer : ServerBuilder() {
        val pubsub = setting("pubSub", PubSub.Settings())

        // Non-blocking handler - doesn't collect, just signals ready
        val simpleHandler = path.path("simple") include object : CoroutineWebsocketHandler() {
            override val pubSub = this@TestServer.pubsub

            context(serverRuntime: ServerRuntime)
            override suspend fun handle(
                request: WebSocketConnectRequest<PathSpec0>,
                waitForFullConnect: suspend () -> Unit,
                incoming: Flow<WebSocketFrame>,
                send: suspend (WebSocketFrame) -> Unit,
            ) {
                println("[SimpleTest] handle() called")
                // Signal ready
                waitForFullConnect()
                println("[SimpleTest] signaled ready, exiting handle (NOT collecting)")
                // DON'T collect - just return to see if this is the issue
            }
        }
    }

    @Test
    fun simple_connect_test(): Unit = runBlocking {
        println("[SimpleTest] Test starting...")
        TestServer.test(settings = { }) {
            println("[SimpleTest] Creating websocket connection...")
            val ws = TestServer.simpleHandler.websocketHandler.test()
            println("[SimpleTest] Connection created successfully!")
            ws.close()
            println("[SimpleTest] Test completed")
        }
    }
}
