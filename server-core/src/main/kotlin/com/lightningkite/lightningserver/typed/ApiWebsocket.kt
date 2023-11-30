package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.authChecked
import com.lightningkite.lightningserver.auth.authOptions
import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.exceptions.BadRequestException
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
import java.net.URLDecoder

data class ApiWebsocket<USER: HasId<*>?, PATH: TypedServerPath, INPUT, OUTPUT>(
    override val path: PATH,
    override val authOptions: AuthOptions<USER>,
    val inputType: KSerializer<INPUT>,
    val outputType: KSerializer<OUTPUT>,
    override val summary: String,
    override val description: String = summary,
    val errorCases: List<LSError>,
    override val belongsToInterface: Documentable.InterfaceInfo? = null,
    val connect: suspend AuthPathPartsAndConnect<USER, PATH, OUTPUT>.() -> Unit,
    val message: suspend TypedWebSocketSender<OUTPUT>.(INPUT) -> Unit,
    val disconnect: suspend TypedWebSocketSender<OUTPUT>.() -> Unit,
) : Documentable, WebSockets.Handler {

    private val wildcards = path.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>()
    override suspend fun connect(event: WebSockets.ConnectEvent) {
        val auth = event.authChecked<USER>(authOptions)
        val receiver = AuthPathPartsAndConnect<USER, PATH, OUTPUT>(
            authOrNull = auth,
            parts = path.serializers.mapIndexed { idx, ser ->
                val name = wildcards.get(idx).name
                val str = event.parts[name] ?: throw BadRequestException("Route segment $name not found")
                str.parseUrlPartOrBadRequest(path.serializers[idx])
            }.toTypedArray(),
            event = event,
            outputSerializer = outputType,
            rawRequest = event
        )
        this.connect.invoke(receiver)
    }

    override suspend fun message(event: WebSockets.MessageEvent) {
        val parsed = event.content.let { Serialization.json.decodeFromString(inputType, it) }
        this.message.invoke(TypedWebSocketSender(event.id, outputType, event.cache), parsed)
    }

    override suspend fun disconnect(event: WebSockets.DisconnectEvent) {
        this.disconnect.invoke(TypedWebSocketSender(event.id, outputType, event.cache))
    }

    suspend fun send(id: WebSocketIdentifier, content: OUTPUT) =
        id.send(Serialization.json.encodeToString(outputType, content))

}

private fun <T> String.parseUrlPartOrBadRequest(serializer: KSerializer<T>): T = try {
    Serialization.fromString(URLDecoder.decode(this, Charsets.UTF_8), serializer)
} catch (e: Exception) {
    throw BadRequestException("Path part ${this} could not be parsed as a ${serializer.descriptor.serialName}.")
}

@LightningServerDsl
inline fun <reified USER: HasId<*>?, PATH: TypedServerPath, reified INPUT, reified OUTPUT> PATH.apiWebsocket(
    summary: String,
    description: String = summary,
    errorCases: List<LSError>,
    belongsToInterface: Documentable.InterfaceInfo? = null,
    noinline connect: suspend AuthPathPartsAndConnect<USER, PATH, OUTPUT>.() -> Unit,
    noinline message: suspend TypedWebSocketSender<OUTPUT>.(INPUT) -> Unit,
    noinline disconnect: suspend TypedWebSocketSender<OUTPUT>.() -> Unit,
): ApiWebsocket<USER, PATH, INPUT, OUTPUT> = apiWebsocket(
    authOptions = authOptions<USER>(),
    inputType = Serialization.module.serializer(),
    outputType = Serialization.module.serializer(),
    summary = summary,
    description = description,
    errorCases = errorCases,
    belongsToInterface = belongsToInterface,
    connect = connect,
    message = message,
    disconnect = disconnect,
)

@LightningServerDsl
fun <USER: HasId<*>?, PATH: TypedServerPath, INPUT, OUTPUT> PATH.apiWebsocket(
    authOptions: AuthOptions<USER>,
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,
    summary: String,
    description: String = summary,
    errorCases: List<LSError>,
    belongsToInterface: Documentable.InterfaceInfo? = null,
    connect: suspend AuthPathPartsAndConnect<USER, PATH, OUTPUT>.() -> Unit,
    message: suspend TypedWebSocketSender<OUTPUT>.(INPUT) -> Unit,
    disconnect: suspend TypedWebSocketSender<OUTPUT>.() -> Unit,
): ApiWebsocket<USER, PATH, INPUT, OUTPUT> {
    val ws = ApiWebsocket(
        path = this,
        authOptions = authOptions,
        inputType = inputType,
        outputType = outputType,
        summary = summary,
        description = description,
        errorCases = errorCases,
        belongsToInterface = belongsToInterface,
        connect = connect,
        message = message,
        disconnect = disconnect,
    )
    WebSockets.handlers[path] = ws
    return ws
}

@LightningServerDsl
inline fun <reified USER: HasId<*>?, reified INPUT, reified OUTPUT> ServerPath.apiWebsocket(
    summary: String,
    description: String = summary,
    errorCases: List<LSError>,
    belongsToInterface: Documentable.InterfaceInfo? = null,
    noinline connect: suspend AuthPathPartsAndConnect<USER, TypedServerPath0, OUTPUT>.() -> Unit,
    noinline message: suspend TypedWebSocketSender<OUTPUT>.(INPUT) -> Unit,
    noinline disconnect: suspend TypedWebSocketSender<OUTPUT>.() -> Unit,
): ApiWebsocket<USER, TypedServerPath0, INPUT, OUTPUT> = apiWebsocket(
    authOptions = authOptions<USER>(),
    inputType = Serialization.module.serializer(),
    outputType = Serialization.module.serializer(),
    summary = summary,
    description = description,
    errorCases = errorCases,
    belongsToInterface = belongsToInterface,
    connect = connect,
    message = message,
    disconnect = disconnect,
)

@LightningServerDsl
fun <USER: HasId<*>?, INPUT, OUTPUT> ServerPath.apiWebsocket(
    authOptions: AuthOptions<USER>,
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,
    summary: String,
    description: String = summary,
    errorCases: List<LSError>,
    belongsToInterface: Documentable.InterfaceInfo? = null,
    connect: suspend AuthPathPartsAndConnect<USER, TypedServerPath0, OUTPUT>.() -> Unit,
    message: suspend TypedWebSocketSender<OUTPUT>.(INPUT) -> Unit,
    disconnect: suspend TypedWebSocketSender<OUTPUT>.() -> Unit,
): ApiWebsocket<USER, TypedServerPath0, INPUT, OUTPUT> {
    val ws = ApiWebsocket(
        path = TypedServerPath0(this),
        authOptions = authOptions,
        inputType = inputType,
        outputType = outputType,
        summary = summary,
        description = description,
        errorCases = errorCases,
        belongsToInterface = belongsToInterface,
        connect = connect,
        message = message,
        disconnect = disconnect,
    )
    WebSockets.handlers[this] = ws
    return ws
}

data class TypedVirtualSocket<INPUT, OUTPUT>(val incoming: ReceiveChannel<OUTPUT>, val send: suspend (INPUT) -> Unit)

suspend fun <USER: HasId<*>?, INPUT, OUTPUT> ApiWebsocket<USER, TypedServerPath0, INPUT, OUTPUT>.test(
    parts: Map<String, String> = mapOf(),
    wildcard: String? = null,
    queryParameters: List<Pair<String, String>> = listOf(),
    headers: HttpHeaders = HttpHeaders.EMPTY,
    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = generalSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "0.0.0.0",
    test: suspend TypedVirtualSocket<INPUT, OUTPUT>.() -> Unit
) {
    this.path.path.test(
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