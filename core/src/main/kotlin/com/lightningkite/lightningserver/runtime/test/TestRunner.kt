package com.lightningkite.lightningserver.runtime.test

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.Initiator
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.ServerRuntimeBase
import com.lightningkite.lightningserver.runtime.forExecution
import com.lightningkite.lightningserver.runtime.phase
import com.lightningkite.lightningserver.settings.ServerSettings
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.SettingContext
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
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
public class TestRunner<SERVER : ServerBuilder> @Deprecated("Please use SERVER.test() instead.") constructor(
    public val serverBuilder: SERVER,
    private val clockGet: () -> Clock = { Clock.System },  // TODO: always use mock clock, build in advancement features
) : ServerRuntimeBase(serverBuilder.build()) {

    public companion object {
        internal val logger = KotlinLogging.logger("com.lightningkite.lightningserver.TestRunner")
    }

    public override val serverId: String = "Test Server"
    public override val serverVersion: String = "N/A"

    override val clock: Clock
        get() = clockGet()

    private val settingsCache = HashMap<ServerSetting<*, *>, Any?>()

    private val subscriptions =
        ConcurrentHashMap<WebSocketSubscriptionRequest<*, *>, ArrayList<suspend (WebSocketSubscriptionMessage<*, *>) -> Unit>>()

    override suspend fun <PATH : PathSpec, T> sendWebSocketSubscriptionMessage(event: WebSocketSubscriptionMessage<PATH, T>) {
        val subscribers =
            subscriptions[WebSocketSubscriptionRequest(topic = event.topic, rawPathArguments = event.rawPathArguments)]
//        /*logger.debug*/run { "'${event.path()}': ${event.value} (${subscribers?.size ?: 0} subscribers)" }.let(::println)
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
     * Runs all registered startup tasks in dependency order (test support).
     *
     * Exposes the protected [runStartupTasks] so tests can exercise startup behavior.
     */
    public suspend fun executeStartupTasks(): Unit = runStartupTasks()

    /**
     * Runs all registered pre-deploy tasks in dependency order (test support).
     *
     * Exposes the protected [runPreDeployTasks] so tests can exercise pre-deploy behavior.
     */
    public suspend fun executePreDeployTasks(): Unit = runPreDeployTasks()

    /**
     * Test wrapper for WebSocket connections.
     *
     * Provides methods to simulate client behavior and inspect server state during testing.
     *
     * @param handler The WebSocket handler being tested
     * @param request The connection request
     * @param initiator The socket's connect initiator, from which each phase derives its own
     * @param currentState The current connection state (mutable for inspection)
     * @param name Display name for debug output (default: "Client")
     */
    @OptIn(InternalLightningServerApi::class)
    public inner class TestWebSocket<PATH : PathSpec, STORAGE>(
        private val handler: WebSocketHandler<PATH, STORAGE>,
        public val request: WebSocketConnectRequest<PATH>,
        public val initiator: Initiator.WebSocket,
        public var currentState: STORAGE,
        public val name: String = "Client",
    ) {
        private fun runtimeFor(phase: Initiator.WebSocket.Phase): ServerRuntime =
            this@TestRunner.forExecution(initiator.phase(phase))

        public var onMessageSent: (frame: WebSocketFrame) -> Unit = {}
        public suspend fun close() {
            /*logger.debug*/run { "$name --> <close>" }.let(::println)
            server.close(WebSocketClose.NORMAL)
            server.clean()
        }

        public suspend fun send(frame: WebSocketFrame) {
            /*logger.debug*/run { "$name --> '$frame'" }.let(::println)
            val connection = this@TestWebSocket.server
            with(runtimeFor(Initiator.WebSocket.Phase.ClientMessage)) { handler.messageFromClient(connection, frame) }
            connection.flush()
        }

        public val server: ServerSide = ServerSide()

        public inner class ServerSide() : WebSocketConnection<PATH, STORAGE> {
            private val changeQueue = ArrayList<(STORAGE) -> STORAGE>()
            private val sub: suspend (WebSocketSubscriptionMessage<*, *>) -> Unit = {
                with(runtimeFor(Initiator.WebSocket.Phase.SubscriptionMessage)) {
                    handler.messageFromSubscription(this@ServerSide, it)
                }
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
                while (changeQueue.isNotEmpty()) {
                    this@TestWebSocket.currentState = changeQueue.removeFirst()(currentState)
                }
                this@TestWebSocket.currentState = modification(currentState)
                return currentState
            }

            internal fun flush() {
                while (changeQueue.isNotEmpty()) {
                    this@TestWebSocket.currentState = changeQueue.removeFirst()(currentState)
                }
            }

            private val topics = HashSet<WebSocketSubscriptionRequest<*, *>>()
            override suspend fun subscribe(topic: WebSocketSubscriptionRequest<*, *>) {
                if (topics.add(topic)) {
                    subscriptions.getOrPut(topic) { ArrayList() }.add(sub)
                }
            }

            override suspend fun unsubscribe(topic: WebSocketSubscriptionRequest<*, *>) {
                if (topics.remove(topic)) {
                    subscriptions.getOrPut(topic) { ArrayList() }.remove(sub)
                }
            }

            override suspend fun send(frame: WebSocketFrame) {
                /*logger.debug*/run { "$name <-- '$frame'" }.let(::println)
                onMessageSent(frame)
            }

            override suspend fun close(reason: WebSocketClose) {
                /*logger.debug*/run { "$name <-- <close>" }.let(::println)
                with(runtimeFor(Initiator.WebSocket.Phase.Disconnect)) { handler.disconnect(this@ServerSide, reason) }
            }

            internal fun clean() {
                for (topic in topics) {
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
public inline fun <SERVER : ServerBuilder> SERVER.test(
    settings: context(ServerSettings) SERVER.(SettingContext) -> Unit,
    noinline clock: () -> Clock = { Clock.System },
    action: context(TestRunner<SERVER>) SERVER.() -> Unit,
) {
    @Suppress("DEPRECATION") val runner = TestRunner(this, clock)
    with(runner) {
        context(this.settings) { settings(this@test, runner) }
        this.settings.readyUsingDefaults()
    }
    action(runner, this)
}

/**
 * Like [test], but the [action] block is a `suspend` lambda, so you can call suspending APIs
 * (every `.test()` helper, database/cache calls, etc.) directly without wrapping the body in
 * `runBlocking` yourself.
 *
 * The "Blocking" in the name reflects that this function blocks the calling thread until the test
 * completes — it runs [action] inside [runBlocking]. Use it from ordinary (non-suspend) `@Test`
 * methods.
 *
 * Example:
 * ```kotlin
 * MyServer.testBlocking(settings = { database.set(Database.Settings("ram")) }) {
 *     val response = myEndpoint.test()        // suspend call — no runBlocking needed
 *     assertEquals(HttpStatus.OK, response.status)
 * }
 * ```
 *
 * @param settings Configuration block for server settings
 * @param action Suspending test code to execute with the test runner as context
 */
public fun <SERVER : ServerBuilder> SERVER.testBlocking(
    settings: context(ServerSettings) SERVER.(SettingContext) -> Unit,
    clock: () -> Clock = { Clock.System },
    action: suspend context(TestRunner<SERVER>) SERVER.() -> Unit,
) {
    @Suppress("DEPRECATION") val runner = TestRunner(this, clock)
    with(runner) {
        context(this.settings) { settings(this@testBlocking, runner) }
        this.settings.readyUsingDefaults()
    }
    runBlocking { action(runner, this@testBlocking) }
}
