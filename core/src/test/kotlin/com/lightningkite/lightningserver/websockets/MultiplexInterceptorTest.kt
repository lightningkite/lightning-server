package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.MultiplexMessage
import com.lightningkite.lightningserver.OverrideOnly
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.cors.CorsInterceptor
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.engine
import com.lightningkite.lightningserver.runtime.isRoot
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
        val physicalMessages: MutableList<String> = Collections.synchronizedList(mutableListOf())
        fun reset() {
            connects.clear()
            disconnects.clear()
            physicalConnects.clear()
            physicalMessages.clear()
        }
    }

    /** Records every logical socket it is given a chance to see, virtual ones included. */
    private object LogicalRecorder : WebSocketInterceptor {
        override val name: String = "LogicalRecorder"

        override fun <PATH : PathSpec, T> intercept(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> =
            object : DelegatingWebSocketHandler<PATH, T>(handler) {
                @OverrideOnly
                context(serverRuntime: ServerRuntime)
                override suspend fun willConnect(request: WebSocketConnectRequest<PATH>): T {
                    Observed.connects.add("/" + request.path.pathSegments.toString())
                    return wrapped.willConnect(request)
                }

                @OverrideOnly
                context(serverRuntime: ServerRuntime)
                override suspend fun disconnect(connection: WebSocketConnection<PATH, T>, reason: WebSocketClose) {
                    Observed.disconnects.add("/" + connection.request.path.pathSegments.toString())
                    wrapped.disconnect(connection, reason)
                }
            }
    }

    /**
     * Narrows itself to the one real socket with [isRoot], the way [CorsInterceptor] does, and so must
     * see nothing multiplexed inside it.
     */
    private object ConnectionRecorder : WebSocketInterceptor {
        override val name: String = "ConnectionRecorder"

        override fun <PATH : PathSpec, T> intercept(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> =
            object : DelegatingWebSocketHandler<PATH, T>(handler) {
                @OverrideOnly
                context(serverRuntime: ServerRuntime)
                override suspend fun willConnect(request: WebSocketConnectRequest<PATH>): T {
                    if (serverRuntime.execution.isRoot())
                        Observed.physicalConnects.add("/" + request.path.pathSegments.toString())
                    return wrapped.willConnect(request)
                }

                @OverrideOnly
                context(serverRuntime: ServerRuntime)
                override suspend fun messageFromClient(
                    connection: WebSocketConnection<PATH, T>,
                    frame: WebSocketFrame,
                ) {
                    if (serverRuntime.execution.isRoot())
                        Observed.physicalMessages.add("/" + connection.request.path.pathSegments.toString())
                    wrapped.messageFromClient(connection, frame)
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
                val json = engine.externalSerialization.json

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
                val json = engine.externalSerialization.json

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
     * The counterpart invariant: an interceptor that guards on [isRoot] decides about the one real
     * socket, so re-running it per virtual socket would repeat a decision already made — and for
     * something like the origin check, repeat it against a request that never crossed the network.
     */
    @Test
    fun `an isRoot-guarded interceptor does not see virtual sockets`() = runBlocking {
        Observed.reset()
        TestServer.test(settings = {}) {
            runBlocking {
                val mux = TestServer.multiplex.test()
                val json = engine.externalSerialization.json

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
                    "an interceptor guarding on isRoot must see only the physical socket",
                )
                assertTrue(
                    "/mirror" in Observed.connects,
                    "an unguarded interceptor should still have seen the virtual socket; saw ${Observed.connects}",
                )
            }
        }
    }

    /**
     * A socket's five phases are five executions, each with its own id and each parented to the
     * connect that opened them. Asking `id == rootExecution` would therefore call every phase after
     * connect a non-root, and an interceptor guarding a later phase would silently never run on the
     * real connection. [isRoot] compares the socket's identity instead, so it answers the same for
     * every phase.
     */
    @Test
    fun `a physical socket is still root after the connect phase`() = runBlocking {
        Observed.reset()
        TestServer.test(settings = {}) {
            runBlocking {
                val mux = TestServer.multiplex.test()

                // Blank frames are answered by the multiplex handler directly, so this exercises the
                // physical socket's messageFromClient phase without opening a channel.
                mux.send(WebSocketFrame.Text(" "))

                assertEquals(
                    listOf("/multiplex"),
                    Observed.physicalMessages,
                    "a later phase of the physical socket was not seen as root",
                )
            }
        }
    }

    /** The other half of the same rule: a virtual socket's later phases are still not root. */
    @Test
    fun `a virtual socket is not root in a later phase either`() = runBlocking {
        Observed.reset()
        TestServer.test(settings = {}) {
            runBlocking {
                val mux = TestServer.multiplex.test()
                val json = engine.externalSerialization.json

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
                            MultiplexMessage(channel = "a", path = "/mirror", data = "hello"),
                        )
                    )
                )

                assertTrue(
                    "/mirror" !in Observed.physicalMessages,
                    "a virtual socket's message phase was treated as root; saw ${Observed.physicalMessages}",
                )
            }
        }
    }
}
