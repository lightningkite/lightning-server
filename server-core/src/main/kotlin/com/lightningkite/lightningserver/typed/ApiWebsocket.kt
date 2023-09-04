package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.authAny
import com.lightningkite.lightningserver.auth.authChecked
import com.lightningkite.lightningserver.auth.authOptions
import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.websocket.WebSocketIdentifier
import com.lightningkite.lightningserver.websocket.WebSockets
import com.lightningkite.lightningserver.websocket.test
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

data class ApiWebsocket<INPUT, OUTPUT>(
    override val path: ServerPath,
    override val authOptions: AuthOptions,
    val inputType: KSerializer<INPUT>,
    val outputType: KSerializer<OUTPUT>,
    override val summary: String,
    override val description: String = summary,
    val errorCases: List<LSError>,
    val routeTypes: Map<String, KSerializer<*>> = mapOf(),
    val connect: suspend ApiWebsocket<INPUT, OUTPUT>.(WebSockets.ConnectEvent) -> Unit,
    val message: suspend ApiWebsocket<INPUT, OUTPUT>.(TypedMessageEvent<INPUT>) -> Unit,
    val disconnect: suspend ApiWebsocket<INPUT, OUTPUT>.(WebSockets.DisconnectEvent) -> Unit,
) : Documentable, WebSockets.Handler {

    class TypedMessageEvent<INPUT>(
        val id: WebSocketIdentifier,
        val cache: Cache,
        val content: INPUT
    )

    override suspend fun connect(event: WebSockets.ConnectEvent) {
        this.connect.invoke(this, event)
    }

    override suspend fun message(event: WebSockets.MessageEvent) {
        val parsed = event.content.let { Serialization.json.decodeFromString(inputType, it) }
        this.message.invoke(this, TypedMessageEvent(event.id, event.cache, parsed))
    }

    override suspend fun disconnect(event: WebSockets.DisconnectEvent) {
        this.disconnect.invoke(this, event)
    }

    suspend fun send(id: WebSocketIdentifier, content: OUTPUT) =
        id.send(Serialization.json.encodeToString(outputType, content))

}

@LightningServerDsl
inline fun <reified USER, reified INPUT, reified OUTPUT> ServerPath.typedWebsocket(
    summary: String,
    description: String = summary,
    errorCases: List<LSError>,
    noinline connect: suspend ApiWebsocket<INPUT, OUTPUT>.(user: USER, WebSockets.ConnectEvent) -> Unit = { _, _ -> },
    noinline message: suspend ApiWebsocket<INPUT, OUTPUT>.(ApiWebsocket.TypedMessageEvent<INPUT>) -> Unit = { },
    noinline disconnect: suspend ApiWebsocket<INPUT, OUTPUT>.(WebSockets.DisconnectEvent) -> Unit = {}
): ApiWebsocket<INPUT, OUTPUT> = typedWebsocket(
    authOptions = authOptions<USER>(),
    inputType = Serialization.module.serializer(),
    outputType = Serialization.module.serializer(),
    summary = summary,
    description = description,
    errorCases = errorCases,
    connect = { connect(it.authChecked(authOptions)?.get() as USER, it) },
    message = message,
    disconnect = disconnect,
)

@LightningServerDsl
fun <INPUT, OUTPUT> ServerPath.typedWebsocket(
    authOptions: AuthOptions,
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,
    summary: String,
    description: String = summary,
    errorCases: List<LSError>,
    connect: suspend ApiWebsocket<INPUT, OUTPUT>.(WebSockets.ConnectEvent) -> Unit = { },
    message: suspend ApiWebsocket<INPUT, OUTPUT>.(ApiWebsocket.TypedMessageEvent<INPUT>) -> Unit = { },
    disconnect: suspend ApiWebsocket<INPUT, OUTPUT>.(WebSockets.DisconnectEvent) -> Unit = {}
): ApiWebsocket<INPUT, OUTPUT> {
    val ws = ApiWebsocket(
        path = this,
        authOptions = authOptions,
        inputType = inputType,
        outputType = outputType,
        summary = summary,
        description = description,
        errorCases = errorCases,
        connect = connect,
        message = message,
        disconnect = disconnect,
    )
    WebSockets.handlers[this] = ws
    return ws
}

data class TypedVirtualSocket<INPUT, OUTPUT>(val incoming: ReceiveChannel<OUTPUT>, val send: suspend (INPUT) -> Unit)

suspend fun <INPUT, OUTPUT> ApiWebsocket<INPUT, OUTPUT>.test(
    parts: Map<String, String> = mapOf(),
    wildcard: String? = null,
    queryParameters: List<Pair<String, String>> = listOf(),
    headers: HttpHeaders = HttpHeaders.EMPTY,
    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = generalSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "0.0.0.0",
    test: suspend TypedVirtualSocket<INPUT, OUTPUT>.() -> Unit
) {
    this.path.test(
        parts = parts,
        wildcard = wildcard,
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
        test = {
            val channel = Channel<OUTPUT>(20)
            coroutineScope {
                val job = launch {
                    for (it in incoming) {
                        channel.send(Serialization.json.decodeFromString(outputType, it))
                    }
                }
                val job2 = launch {
                    test(TypedVirtualSocket<INPUT, OUTPUT>(
                        incoming = channel,
                        send = { send(Serialization.json.encodeToString(inputType, it)) }
                    ))
                }
                job2.join()
                job.cancelAndJoin()
            }
        },
    )
}