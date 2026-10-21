package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.MultiplexMessage
import com.lightningkite.lightningserver.OverrideOnly
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.Execution
import com.lightningkite.lightningserver.runtime.ExecutionInterceptor
import com.lightningkite.lightningserver.runtime.Invocation
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.engine
import com.lightningkite.lightningserver.runtime.test.test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A multiplexed connection is one physical socket carrying many virtual ones. The WebSocket chain
 * decides about the physical socket only, while the execution chain sees every virtual socket too,
 * with the request that opened it, so access logging and auditing still cover them.
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

    /** Records the open and close of every socket, virtual ones included. */
    private object LogicalRecorder : ExecutionInterceptor {
        override val name: String = "LogicalRecorder"

        context(runtime: ServerRuntime)
        override suspend fun <T> intercept(invocation: Invocation, cont: suspend context(ServerRuntime) () -> T): T {
            if (invocation is Invocation.WebSocket) {
                val path = "/" + invocation.request.path.pathSegments.toString()
                when (invocation.execution.phase) {
                    Execution.WebSocket.Phase.Connect -> Observed.connects.add(path)
                    Execution.WebSocket.Phase.Disconnect -> Observed.disconnects.add(path)
                    else -> {}
                }
            }
            return cont()
        }
    }

    /** Records whatever the WebSocket chain hands it, which should only ever be the physical socket. */
    private object PhysicalRecorder : WebSocketInterceptor {
        override val name: String = "PhysicalRecorder"

        override fun <PATH : PathSpec, T> intercept(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> =
            object : DelegatingWebSocketHandler<PATH, T>(handler) {
                @OverrideOnly
                context(serverRuntime: ServerRuntime)
                override suspend fun willConnect(request: WebSocketConnectRequest<PATH>): T {
                    Observed.physicalConnects.add("/" + request.path.pathSegments.toString())
                    return wrapped.willConnect(request)
                }

                @OverrideOnly
                context(serverRuntime: ServerRuntime)
                override suspend fun messageFromClient(
                    connection: WebSocketConnection<PATH, T>,
                    frame: WebSocketFrame,
                ) {
                    Observed.physicalMessages.add("/" + connection.request.path.pathSegments.toString())
                    wrapped.messageFromClient(connection, frame)
                }
            }
    }

    object TestServer : ServerBuilder() {
        init {
            install(LogicalRecorder)
            install(PhysicalRecorder)
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
    fun `a virtual socket passes through the execution interceptor chain`() = runBlocking {
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
                    "the virtual socket bypassed the execution interceptors; saw ${Observed.connects}",
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
                    "closing a virtual socket was not observed by the execution interceptors",
                )
            }
        }
    }

    /**
     * The counterpart invariant: the WebSocket chain decides about the one real socket, so re-running
     * it per virtual socket would repeat a decision already made — and for something like the origin
     * check, repeat it against a request that never crossed the network.
     */
    @Test
    fun `the webSocket interceptor chain does not see virtual sockets`() = runBlocking {
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
                    "the webSocket interceptor chain must see only the physical socket",
                )
            }
        }
    }

    /** The WebSocket chain wraps every phase of the physical socket, not only its connect. */
    @Test
    fun `the webSocket interceptor chain sees later phases of the physical socket`() = runBlocking {
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
                    "a later phase of the physical socket skipped the webSocket interceptor chain",
                )
            }
        }
    }

    /** The other half of the same rule: a virtual socket's later phases skip the chain too. */
    @Test
    fun `the webSocket interceptor chain does not see later phases of a virtual socket`() = runBlocking {
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
                    "a virtual socket's message phase went through the webSocket interceptor chain; saw ${Observed.physicalMessages}",
                )
            }
        }
    }
}
