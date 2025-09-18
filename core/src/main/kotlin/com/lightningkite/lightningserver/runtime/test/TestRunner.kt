package com.lightningkite.lightningserver.runtime.test

import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
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
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.time.Clock

public class TestRunner<SERVER: ServerBuilder> @Deprecated("Please use SERVER.test() instead.") constructor (
    public val serverBuilder: SERVER,
    private val clockGet: () -> Clock = { Clock.System }
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
        /*logger.debug*/run { "'${event.path(externalSerialization.stringArrayFormat)}': ${event.value} (${subscribers?.size ?: 0} subscribers)" }.let(::println)
        subscribers?.forEach {
            it(event)
        }
    }

    override suspend fun <T> Task<T>.invoke(input: T) {
        this.execute(input)
    }

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
                /*logger.debug*/run { "$name subscribes to '${topic.path(externalSerialization.stringArrayFormat)}'" }.let(::println)
                if(topics.add(topic)) {
                    subscriptions.getOrPut(topic) { ArrayList() }.add(sub)
                }
            }

            override suspend fun unsubscribe(topic: WebSocketSubscriptionRequest<*, *>) {
                /*logger.debug*/run { "$name unsubscribes from '${topic.path(externalSerialization.stringArrayFormat)}'" }.let(::println)
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
