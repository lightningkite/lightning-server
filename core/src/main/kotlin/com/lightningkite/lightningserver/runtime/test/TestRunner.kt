package com.lightningkite.lightningserver.runtime.test

import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.path
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.ServerRuntimeBase
import com.lightningkite.lightningserver.settings.ServerSettings
import com.lightningkite.lightningserver.websockets.WebSocketClose
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.lightningserver.websockets.WebSocketConnection
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock

/**
 * Test runtime for Lightning Server applications.
 *
 * TestRunner provides an in-memory server runtime for unit testing that:
 * - Executes handlers synchronously for deterministic testing
 * - Tracks WebSocket subscriptions in memory
 * - Allows clock injection for time-based testing
 * - Prints debug output for WebSocket messages
 * - Executes tasks inline (not in background)
 *
 * ## Usage
 * Prefer the extension function approach:
 * ```kotlin
 * MyServer.test(
 *     settings = { /* configure settings */ }
 * ) {
 *     val response = myEndpoint.test()
 *     assertEquals(expectedValue, response)
 * }
 * ```
 *
 * The direct constructor is deprecated in favor of the extension function.
 *
 * ## Testing WebSockets
 * WebSocket handlers return a [TestWebSocket] instance that provides:
 * - `send(frame)` to send messages to the server
 * - `close()` to close the connection
 * - `onMessageSent` callback to capture server messages
 * - `currentState` to inspect the connection state
 *
 * @param serverBuilder The server definition to test
 * @param clockGet Optional clock provider for testing time-dependent code
 */
public class TestRunner<SERVER: ServerBuilder> @Deprecated("Please use SERVER.test() instead.") constructor (
    public val serverBuilder: SERVER,
    private val clockGet: () -> Clock = { Clock.System }  // TODO: always use mock clock, build in advancement features
) : ServerRuntimeBase(serverBuilder.build()) {

    public companion object {
        internal val logger = KotlinLogging.logger("com.lightningkite.lightningserver.TestRunner")
    }

    public override val serverId:String = "Test Server"
    public override val serverVersion:String =  "N/A"

    override val clock: Clock
        get() = clockGet()

    private val settingsCache = HashMap<ServerSetting<*, *>, Any?>()

    private val subscriptions = ConcurrentHashMap<WebSocketSubscriptionRequest<*, *>, ArrayList<suspend (WebSocketSubscriptionMessage<*, *>)->Unit>>()
    override suspend fun <PATH : PathSpec, T> sendWebSocketSubscriptionMessage(event: WebSocketSubscriptionMessage<PATH, T>) {
        val subscribers = subscriptions[WebSocketSubscriptionRequest(topic = event.topic, rawPathArguments = event.rawPathArguments)]
        /*logger.debug*/run { "'${event.path()}': ${event.value} (${subscribers?.size ?: 0} subscribers)" }.let(::println)
        subscribers?.forEach {
            it(event)
        }
    }

    /**
     * Executes tasks inline (synchronously) for testing.
     *
     * Unlike production runtimes, tasks don't run in the background but complete
     * immediately, making tests deterministic.
     */
    override suspend fun <T> Task<T>.invoke(input: T) {
        this.executeInline(input)
    }

    /**
     * Test wrapper for WebSocket connections.
     *
     * Provides methods to simulate client behavior and inspect server state during testing.
     *
     * @param handler The WebSocket handler being tested
     * @param request The connection request
     * @param currentState The current connection state (mutable for inspection)
     * @param name Display name for debug output (default: "Client")
     */
    public inner class TestWebSocket<PATH: PathSpec, STORAGE>(
        private val handler: WebSocketHandler<PATH, STORAGE>,
        public val request: WebSocketConnectRequest<PATH>,
        public var currentState: STORAGE,
        public val name: String = "Client"
    ) {
        public var onMessageSent: (frame: WebSocketFrame)->Unit = {}
        public suspend fun close() {
            /*logger.debug*/run { "$name --> <close>" }.let(::println)
            server.close(WebSocketClose.NORMAL)
            server.clean()
        }

        public suspend fun send(frame: WebSocketFrame) {
            /*logger.debug*/run { "$name --> '$frame'" }.let(::println)
            with(server) {
                handler.messageFromClient(frame)
                flush()
            }
        }

        public val server: ServerSide = ServerSide()
        public inner class ServerSide(): WebSocketConnection<PATH, STORAGE>, ServerRuntime by this@TestRunner {
            private val changeQueue = ArrayList<(STORAGE)->STORAGE>()
            private val sub: suspend (WebSocketSubscriptionMessage<*, *>) -> Unit = {
                handler.messageFromSubscription(it)
                flush()
            }

            override val currentState: STORAGE
                get() = this@TestWebSocket.currentState
            override val request: WebSocketConnectRequest<PATH>
                get() = this@TestWebSocket.request
            override suspend fun repullState(): STORAGE = currentState

            override suspend fun queueStateUpdate(modification: (STORAGE) -> STORAGE) {
                changeQueue.add(modification)
            }

            override suspend fun updateStateImmediately(modification: (STORAGE) -> STORAGE): STORAGE {
                while(changeQueue.isNotEmpty()) {
                    this@TestWebSocket.currentState = changeQueue.removeFirst()(currentState)
                }
                this@TestWebSocket.currentState = modification(currentState)
                return currentState
            }

            internal fun flush() {
                while(changeQueue.isNotEmpty()) {
                    this@TestWebSocket.currentState = changeQueue.removeFirst()(currentState)
                }
            }

            private val topics = HashSet<WebSocketSubscriptionRequest<*, *>>()
            override suspend fun subscribe(topic: WebSocketSubscriptionRequest<*, *>) {
                if(topics.add(topic)) {
                    subscriptions.getOrPut(topic) { ArrayList() }.add(sub)
                }
            }

            override suspend fun unsubscribe(topic: WebSocketSubscriptionRequest<*, *>) {
                if(topics.remove(topic)) {
                    subscriptions.getOrPut(topic) { ArrayList() }.remove(sub)
                }
            }

            override suspend fun send(frame: WebSocketFrame) {
                /*logger.debug*/run { "$name <-- '$frame'" }.let(::println)
                onMessageSent(frame)
            }

            override suspend fun close(reason: WebSocketClose) {
                /*logger.debug*/run { "$name <-- <close>" }.let(::println)
                handler.disconnect(reason)
            }

            internal fun clean() {
                for(topic in topics) {
                    subscriptions[topic]?.remove(sub)
                }
                topics.clear()
            }
        }
    }
}

/**
 * Creates a test environment for a server and executes test code.
 *
 * This is the preferred way to test Lightning Server applications. It:
 * 1. Creates a TestRunner instance
 * 2. Allows configuring settings via the `settings` block
 * 3. Initializes settings with defaults
 * 4. Executes the test `action` with the runner as context
 *
 * Example:
 * ```kotlin
 * MyServer.test(
 *     settings = {
 *         database.set(Database.JsonFile("test-db.json"))
 *     }
 * ) {
 *     val response = myEndpoint.test()
 *     assertEquals(HttpStatus.OK, response.status)
 * }
 * ```
 *
 * @param settings Configuration block for server settings
 * @param action Test code to execute with the test runner as context
 */
public inline fun <SERVER: ServerBuilder> SERVER.test(
    settings: context(ServerSettings) SERVER.() -> Unit,
    action: context(TestRunner<SERVER>) SERVER.()->Unit
) {
    @Suppress("DEPRECATION") val runner = TestRunner(this)
    with(runner) {
        context(this.settings) { settings(this@test) }
        this.settings.readyUsingDefaults()
    }
    action(runner, this)
}
