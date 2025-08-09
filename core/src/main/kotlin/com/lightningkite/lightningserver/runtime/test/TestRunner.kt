package com.lightningkite.lightningserver.runtime.test

import com.lightningkite.lightningserver.Task
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.pathing.PathSpec2
import com.lightningkite.lightningserver.pathing.PathSpec3
import com.lightningkite.lightningserver.pathing.ServerPath
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.ServerRuntimeBase
import com.lightningkite.lightningserver.runtime.invoke
import com.lightningkite.lightningserver.websockets.WebSocketClose
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.lightningserver.websockets.WebSocketConnection
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionRequest
import com.lightningkite.services.data.TypedData
import kotlinx.coroutines.flow.MutableSharedFlow

public class TestRunner<SERVER: ServerBuilder>(
    public val serverBuilder: SERVER,
    public val settings: TestSettings,
) : ServerRuntimeBase(serverBuilder.build().flatten()) {
    public constructor(
        server: SERVER,
        settings: context(TestSettings) SERVER.() -> Unit
    ): this(server, with(server) { TestSettings().apply { settings() } })

    private val settingsCache = HashMap<ServerSetting<*, *>, Any?>()

    @Suppress("UNCHECKED_CAST")
    override fun <SERIALIZABLE, GOAL> ServerSetting<SERIALIZABLE, GOAL>.invoke(): GOAL {
        return settingsCache.getOrPut(this) {
            (settings.goal[this] as? GOAL) ?: run {
                val value = settings.serializable.getValue(this) as SERIALIZABLE
                val result: GOAL = this.get(value)
                result
            }
        } as GOAL
    }

    private val subscriptions = HashMap<WebSocketSubscriptionRequest<*, *>, ArrayList<suspend (WebSocketSubscriptionMessage<*, *>)->Unit>>()
    override suspend fun <PATH : PathSpec, T> sendWebSocketSubscriptionMessage(event: WebSocketSubscriptionMessage<PATH, T>) {
        subscriptions[WebSocketSubscriptionRequest(topic = event.topic, rawPathArguments = event.rawPathArguments)]?.forEach {
            it(event)
        }
    }

    override suspend fun <T> Locationed<PathSpec0, Task<T>>.invoke(input: T) {
        this.item.execute(input)
    }

    public inner class TestWebSocket<PATH: PathSpec, STORAGE>(
        private val handler: WebSocketHandler<PATH, STORAGE>,
        public val request: WebSocketConnectRequest<PATH>,
        public var currentState: STORAGE
    ) {
        public var onMessageSent: (frame: WebSocketFrame)->Unit = {}
        public val messages: MutableSharedFlow<WebSocketFrame> = MutableSharedFlow()
        public suspend fun close() {
            server.close(WebSocketClose.NORMAL)
            server.clean()
        }

        public suspend fun send(frame: WebSocketFrame) {
            handler.messageFromClient(server, frame)
        }

        public val server: ServerSide = ServerSide()
        public inner class ServerSide(): WebSocketConnection<PATH, STORAGE>, ServerRuntime by this@TestRunner {
            private val changeQueue = ArrayList<(STORAGE)->STORAGE>()
            private val sub: suspend (WebSocketSubscriptionMessage<*, *>) -> Unit = {
                handler.messageFromSubscription(this, it)
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
                messages.tryEmit(frame)
                onMessageSent(frame)
            }

            override suspend fun close(reason: WebSocketClose) {
                handler.disconnect(this, reason)
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
    settings: context(TestSettings) SERVER.() -> Unit,
    action: context(TestRunner<SERVER>) SERVER.()->Unit
) {
    val runner = TestRunner(this, TestSettings().apply { settings() })
    action(runner, this)
}