package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.MultiplexMessage
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `MultiplexWebSocketHandler` used to dispatch each virtual socket to the raw handler resolved from
 * the route table, so every multiplexed socket bypassed the WebSocket interceptor chain — access
 * logging and rate limiting among them. It is the same defect `/meta/bulk` had on the HTTP side: many
 * logical connections executing while the pipeline saw one.
 */
class MultiplexInterceptorTest {

    private object Observed {
        val connects: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val disconnects: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val physicalConnects: MutableList<String> = Collections.synchronizedList(mutableListOf())
        fun reset() {
            connects.clear()
            disconnects.clear()
            physicalConnects.clear()
        }
    }

    /** Records which sockets it was given a chance to see. */
    /** Records every logical socket it is given a chance to see, virtual ones included. */
    private object LogicalRecorder : WebSocketLogicalInterceptor {
        override val name: String = "LogicalRecorder"

        override fun <PATH : PathSpec, T> intercept(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> =
            object : DelegatingWebSocketHandler<PATH, T>(handler) {
                context(serverRuntime: ServerRuntime)
                override suspend fun willConnect(request: WebSocketConnectRequest<PATH>): T {
                    Observed.connects.add("/" + request.path.pathSegments.toString())
                    return wrapped.willConnect(request)
                }

                context(serverRuntime: ServerRuntime)
                override suspend fun disconnect(connection: WebSocketConnection<PATH, T>, reason: WebSocketClose) {
                    Observed.disconnects.add("/" + connection.request.path.pathSegments.toString())
                    wrapped.disconnect(connection, reason)
                }
            }
    }

    /** Must see the one real socket and nothing multiplexed inside it. */
    private object ConnectionRecorder : WebSocketConnectionInterceptor {
        override val name: String = "ConnectionRecorder"

        override fun <PATH : PathSpec, T> intercept(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> =
            object : DelegatingWebSocketHandler<PATH, T>(handler) {
                context(serverRuntime: ServerRuntime)
                override suspend fun willConnect(request: WebSocketConnectRequest<PATH>): T {
                    Observed.physicalConnects.add("/" + request.path.pathSegments.toString())
                    return wrapped.willConnect(request)
                }
            }
    }

    object TestServer : ServerBuilder() {
        init {
            install(LogicalRecorder)
            install(ConnectionRecorder)
        }

        val mirror = path.path("mirror") bind WebSocketHandler(
            storageSerializer = Unit.serializer(),
            willConnect = { Unit },
            didConnect = {},
            topicHandlers = {},
            messageFromClient = { frame -> send(frame) },
            disconnect = {}
        )
        val multiplex = path.path("multiplex") bind MultiplexWebSocketHandler()
    }

    @Test
    fun `a virtual socket passes through the webSocket interceptor chain`() = runBlocking {
        Observed.reset()
        TestServer.test(settings = {}) {
            runBlocking {
                val mux = TestServer.multiplex.test()
                val json = contextOf<ServerRuntime>().externalSerialization.json

                mux.send(
                    WebSocketFrame.Text(
                        json.encodeToString(
                            MultiplexMessage.serializer(),
                            MultiplexMessage(channel = "a", path = "/mirror", start = true),
                        )
                    )
                )

                assertTrue(
                    "/mirror" in Observed.connects,
                    "the virtual socket bypassed the interceptor chain; saw ${Observed.connects}",
                )
            }
        }
    }

    @Test
    fun `closing a virtual socket is also observed`() = runBlocking {
        Observed.reset()
        TestServer.test(settings = {}) {
            runBlocking {
                val mux = TestServer.multiplex.test()
                val json = contextOf<ServerRuntime>().externalSerialization.json

                mux.send(
                    WebSocketFrame.Text(
                        json.encodeToString(
                            MultiplexMessage.serializer(),
                            MultiplexMessage(channel = "a", path = "/mirror", start = true),
                        )
                    )
                )
                mux.send(
                    WebSocketFrame.Text(
                        json.encodeToString(
                            MultiplexMessage.serializer(),
                            MultiplexMessage(channel = "a", path = "/mirror", end = true),
                        )
                    )
                )

                assertEquals(
                    listOf("/mirror"),
                    Observed.disconnects,
                    "closing a virtual socket was not observed by the interceptor chain",
                )
            }
        }
    }

    /**
     * The counterpart invariant: a connection-scoped interceptor decides about the one real socket,
     * so re-running it per virtual socket would repeat a decision already made — and for something
     * like the origin check, repeat it against a request that never crossed the network.
     */
    @Test
    fun `a connection scoped interceptor does not see virtual sockets`() = runBlocking {
        Observed.reset()
        TestServer.test(settings = {}) {
            runBlocking {
                val mux = TestServer.multiplex.test()
                val json = contextOf<ServerRuntime>().externalSerialization.json

                mux.send(
                    WebSocketFrame.Text(
                        json.encodeToString(
                            MultiplexMessage.serializer(),
                            MultiplexMessage(channel = "a", path = "/mirror", start = true),
                        )
                    )
                )

                assertEquals(
                    listOf("/multiplex"),
                    Observed.physicalConnects,
                    "connection-scoped interceptors must see only the physical socket",
                )
                assertTrue(
                    "/mirror" in Observed.connects,
                    "the logical chain should still have seen the virtual socket; saw ${Observed.connects}",
                )
            }
        }
    }
}
