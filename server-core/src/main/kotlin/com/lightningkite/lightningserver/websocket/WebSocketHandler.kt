package com.lightningkite.lightningserver.websocket

import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.engine.engine
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.Request
import com.lightningkite.lightningserver.serialization.TypeRetriever
import io.ktor.util.encodeBase64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

interface WebSocketHandler<STORAGE> {
    val storageSerializer: KSerializer<STORAGE>
    suspend fun willConnect(request: WebSocketConnectRequest): STORAGE
    suspend fun didConnect(connection: WebSocketConnection<STORAGE>)
    suspend fun messageFromClient(connection: WebSocketConnection<STORAGE>, frame: WebSocketFrame)
    suspend fun messageFromSubscription(connection: WebSocketConnection<STORAGE>, topic: String, retrieve: TypeRetriever)
    suspend fun disconnect(connection: WebSocketConnection<STORAGE>, reason: WebSocketClose)
}

data class WebSocketTopic<T>(
    val topic: String,
    val type: KSerializer<T>
) {
    suspend fun publish(value: T) = engine.publish(topic, type, value)
}

sealed interface WebSocketFrame {
    val content: Any

    companion object {
        operator fun invoke(content: String) = Text(content)
        operator fun invoke(content: ByteArray) = Binary(content)
    }

    @JvmInline
    value class Text(override val content: String) : WebSocketFrame {
        override fun toString(): String = content
    }

    @JvmInline
    value class Binary(override val content: ByteArray) : WebSocketFrame {
        @OptIn(ExperimentalStdlibApi::class)
        override fun toString(): String = "<bytes ${content.toHexString()}>"
    }
}

val WebSocketFrame.text: String
    get() = when (this) {
        is WebSocketFrame.Binary -> content.encodeBase64()
        is WebSocketFrame.Text -> content
    }

@Serializable
data class WebSocketConnectRequest(
    override val path: ServerPath,
    override val parts: Map<String, String> = mapOf(),
    override val wildcard: String? = null,
    override val queryParameters: List<Pair<String, String>> = listOf(),
    override val headers: HttpHeaders = HttpHeaders.EMPTY,
    override val domain: String = "",
    override val protocol: String = "",
    override val sourceIp: String = "",
    @Transient val cache: Cache = LocalCache(),
) : Request {
    fun queryParameter(key: String): String? = queryParameters.find { it.first == key }?.second
    fun queryParameterCaseInsensitive(key: String): String? = queryParameters.find { it.first.equals(key, true) }?.second
    @Transient private val cacheCalc = HashMap<Request.CacheKey<*>, Any?>()
    override suspend fun <T> cache(key: Request.CacheKey<T>): T {
        @Suppress("UNCHECKED_CAST")
        if (cacheCalc.containsKey(key)) return cacheCalc[key] as T
        val calculated = key.calculate(this)
        cacheCalc[key] = calculated
        return calculated
    }
}

interface WebSocketConnection<STORAGE> {
    val request: WebSocketConnectRequest
    val currentState: STORAGE
    suspend fun repullState(): STORAGE
    suspend fun queueStateUpdate(modification: (STORAGE) -> STORAGE)
    suspend fun updateStateImmediately(modification: (STORAGE) -> STORAGE): STORAGE
    suspend fun <T> subscribe(topic: WebSocketTopic<T>)
    suspend fun unsubscribe(topic: String)
    suspend fun send(frame: WebSocketFrame)
    suspend fun close(reason: WebSocketClose)
}

suspend fun WebSocketConnection<*>.send(content: String) = send(WebSocketFrame(content))
suspend fun WebSocketConnection<*>.send(content: ByteArray) = send(WebSocketFrame(content))

/*
TODO:

- Ktor Engine, back subscribe with PubSub
- Multiplex
- Query Param
- Change websockets
- AWS Adapter, run 'publish' locally and execute all of the websocket's messageFromSubscription immediately, store list of subscribing socket IDs with Dynamo

 */