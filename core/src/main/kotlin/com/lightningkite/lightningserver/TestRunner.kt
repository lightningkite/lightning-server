package com.lightningkite.lightningserver

import com.lightningkite.lightningserver.PathServer.Companion.invoke
import com.lightningkite.lightningserver.TestRunner.TestWebSocket
import com.lightningkite.serviceabstractions.data.TypedData
import com.sun.org.apache.xml.internal.serializer.utils.Utils.messages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext

public inline fun <SD: ServerDefinition> SD.test(
    noinline runSuspending: (suspend CoroutineScope.() -> Unit) -> Unit = { runBlocking { it(this) } },
    settings: context(SettingsBuilder) SD.() -> Unit,
    action: context(TestRunner<SD>) SD.()->Unit
) {
    val runner = TestRunner(this, runSuspending, SettingsBuilder().apply { settings() }.build())
    action(runner, this)
}

public class SettingsBuilder() {
    private val settings = HashMap<Locationed<PathSpec0, ServerSetting<*, *>>, Any?>()
    public infix fun <SERIALIZABLE> Locationed<PathSpec0, ServerSetting<SERIALIZABLE, *>>.set(value: SERIALIZABLE) {
        settings[this] = value as Any?
    }
    public infix fun <RESULT> Locationed<PathSpec0, ServerSetting<*, RESULT>>.setStatic(value: RESULT) {
        settings[this] = value as Any?
    }
    public fun build(): Map<Locationed<PathSpec0, ServerSetting<*, *>>, Any?> = settings
}
context(builder: SettingsBuilder) public infix fun <SERIALIZABLE> Locationed<PathSpec0, ServerSetting<SERIALIZABLE, *>>.set(value: SERIALIZABLE) {
    with(builder) { this@set set value }
}

public class TestRunner<SD: ServerDefinition>(
    override val server: SD,
    public val runSuspending: (suspend CoroutineScope.() -> Unit) -> Unit = { runBlocking { it(this) } },
    public val settings: Map<Locationed<PathSpec0, ServerSetting<*, *>>, Any?>
) : ServerRunning {
    public constructor(
        server: SD,
        runSuspending: (suspend CoroutineScope.() -> Unit) -> Unit = { runBlocking { it(this) } },
        settings: context(SettingsBuilder) SD.() -> Unit
    ): this(server, runSuspending, with(server) { SettingsBuilder().apply { settings() }.build() })


    private val settingsCache = HashMap<Locationed<PathSpec0, ServerSetting<*, *>>, Any?>()

    @Suppress("UNCHECKED_CAST")
    override fun <SERIALIZABLE, GOAL> Locationed<PathSpec0, ServerSetting<SERIALIZABLE, GOAL>>.invoke(): GOAL {
        return settingsCache.getOrPut(this) {
            val value = settings.getValue(this) as SERIALIZABLE
            val result: GOAL = this.item.getter(this@TestRunner, this.location.toString(), value)
            result
        } as GOAL
    }

    private val subscriptions = HashMap<WebSocketSubscriptionRequest<*, *>, ArrayList<suspend (WebSocketSubscriptionMessage<*, *>)->Unit>>()
    override suspend fun <PATH : PathSpec, T> sendWebSocketSubscriptionMessage(event: WebSocketSubscriptionMessage<PATH, T>) {
        subscriptions[WebSocketSubscriptionRequest(topic = event.topic, rawPathArguments = event.rawPathArguments)]?.forEach {
            it(event)
        }
    }


    public inner class TestWebSocket<PATH: PathSpec, STORAGE>(
        private val handler: WebSocketHandler<PATH, STORAGE>,
        public val request: WebSocketConnectRequest<PATH>,
        public var currentState: STORAGE
    ) {
        public val messages: MutableSharedFlow<WebSocketFrame> = MutableSharedFlow()

        public suspend fun send(frame: WebSocketFrame) {
            handler.messageFromClient(server, frame)
        }

        public val server: ServerSide = ServerSide()
        public inner class ServerSide(): WebSocketConnection<PATH, STORAGE>, ServerRunning by this@TestRunner {
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

            override suspend fun subscribe(topic: WebSocketSubscriptionRequest<*, *>) {
                subscriptions.getOrPut(topic) { ArrayList() }.add(sub)
            }

            override suspend fun unsubscribe(topic: WebSocketSubscriptionRequest<*, *>) {
                subscriptions.getOrPut(topic) { ArrayList() }.remove(sub)
            }

            override suspend fun send(frame: WebSocketFrame) {
                messages.emit(frame)
            }

            override suspend fun close(reason: WebSocketClose) {
                handler.disconnect(this, reason)
            }
        }
    }


}

context(test: TestRunner<*>) public fun runSuspending(action: suspend CoroutineScope.() -> Unit) {
    test.runSuspending(action)
}

context(test: TestRunner<*>) public suspend fun <PATH: PathSpec, T> sendWebSocketSubscriptionMessage(message: WebSocketSubscriptionMessage<PATH, T>) {
    test.sendWebSocketSubscriptionMessage(message)
}

context(test: TestRunner<*>) public suspend fun <STORAGE> Locationed<PathSpec0, WebSocketHandler<PathSpec0, STORAGE>>.test(
    queryParameters: List<Pair<String, String>> = listOf(),
    headers: HttpHeaders = HttpHeaders.EMPTY,
    domain: String = test.server.generalServerSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = test.server.generalServerSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "local",
): TestRunner<*>.TestWebSocket<PathSpec0, STORAGE> {
    val request = WebSocketConnectRequest(
        PathServer(this.location),
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
    )
    val storage = item.willConnect(
        test, request
    )
    return test.TestWebSocket(item, request, storage)
}
context(test: TestRunner<*>) public suspend fun Locationed<HttpEndpoint<PathSpec0>, HttpHandler<PathSpec0>>.test(
    queryParameters: List<Pair<String, String>> = listOf(),
    headers: HttpHeaders = HttpHeaders.EMPTY,
    domain: String = test.server.generalServerSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = test.server.generalServerSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "local",
    body: TypedData? = null,
): HttpResponse {
    return this.item.handle(
        test, HttpRequest(
            PathServer(this.location.path),
            method = this.location.method,
            queryParameters = queryParameters,
            headers = headers,
            domain = domain,
            protocol = protocol,
            sourceIp = sourceIp,
            body = body,
        )
    )
}

context(test: TestRunner<*>) public suspend fun <A> Locationed<HttpEndpoint<PathSpec1<A>>, HttpHandler<PathSpec1<A>>>.test(
    path1: A,
    queryParameters: List<Pair<String, String>> = listOf(),
    headers: HttpHeaders = HttpHeaders.EMPTY,
    domain: String = test.server.generalServerSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = test.server.generalServerSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "local",
    body: TypedData? = null,
): HttpResponse {
    return this.item.handle(
        test, HttpRequest(
            PathServer(this.location.path, path1),
            method = this.location.method,
            queryParameters = queryParameters,
            headers = headers,
            domain = domain,
            protocol = protocol,
            sourceIp = sourceIp,
            body = body,
        )
    )
}

context(test: TestRunner<*>) public suspend fun <A, B> Locationed<HttpEndpoint<PathSpec2<A, B>>, HttpHandler<PathSpec2<A, B>>>.test(
    path1: A,
    path2: B,
    queryParameters: List<Pair<String, String>> = listOf(),
    headers: HttpHeaders = HttpHeaders.EMPTY,
    domain: String = test.server.generalServerSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = test.server.generalServerSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "local",
    body: TypedData? = null,
): HttpResponse {
    return this.item.handle(
        test, HttpRequest(
            PathServer(this.location.path, path1, path2),
            method = this.location.method,
            queryParameters = queryParameters,
            headers = headers,
            domain = domain,
            protocol = protocol,
            sourceIp = sourceIp,
            body = body,
        )
    )
}

context(test: TestRunner<*>) public suspend fun <A, B, C> Locationed<HttpEndpoint<PathSpec3<A, B, C>>, HttpHandler<PathSpec3<A, B, C>>>.test(
    path1: A,
    path2: B,
    path3: C,
    queryParameters: List<Pair<String, String>> = listOf(),
    headers: HttpHeaders = HttpHeaders.EMPTY,
    domain: String = test.server.generalServerSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = test.server.generalServerSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "local",
    body: TypedData? = null,
): HttpResponse {
    return this.item.handle(
        test, HttpRequest(
            PathServer(this.location.path, path1, path2, path3),
            method = this.location.method,
            queryParameters = queryParameters,
            headers = headers,
            domain = domain,
            protocol = protocol,
            sourceIp = sourceIp,
            body = body,
        )
    )
}