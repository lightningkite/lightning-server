package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.parse
import com.lightningkite.lightningserver.serialization.serializerOrContextual
import com.lightningkite.lightningserver.settings.GeneralServerSettings
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.websocket.VirtualSocket
import com.lightningkite.lightningserver.websocket.WebSocketClose
import com.lightningkite.lightningserver.websocket.WebSockets
import com.lightningkite.lightningserver.websocket.test
import io.ktor.util.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelIterator
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.selects.SelectClause1
import kotlinx.html.INPUT
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.serializer
import java.util.*
import kotlin.reflect.KType
import kotlin.reflect.typeOf

data class ApiWebsocket<USER, INPUT, OUTPUT>(
    override val path: ServerPath,
    override val authInfo: AuthInfo<USER>,
    val inputType: KSerializer<INPUT>,
    val outputType: KSerializer<OUTPUT>,
    override val summary: String,
    override val description: String = summary,
    val errorCases: List<ErrorCase>,
    val routeTypes: Map<String, KSerializer<*>> = mapOf(),
    val connect: suspend ApiWebsocket<USER, INPUT, OUTPUT>.(TypedConnectEvent<USER>) -> Unit,
    val message: suspend ApiWebsocket<USER, INPUT, OUTPUT>.(TypedMessageEvent<INPUT>) -> Unit,
    val disconnect: suspend ApiWebsocket<USER, INPUT, OUTPUT>.(WebSockets.DisconnectEvent) -> Unit,
) : Documentable, WebSockets.Handler {

    data class TypedConnectEvent<USER>(val user: USER, val id: String)
    data class TypedMessageEvent<INPUT>(val id: String, val content: INPUT)

    data class ErrorCase(val closeReason: WebSocketClose, val internalCode: Int, val description: String)

    override suspend fun connect(event: WebSockets.ConnectEvent) {
        this.connect.invoke(this, TypedConnectEvent(authInfo.checker(event.rawUser()), event.id))
    }

    override suspend fun message(event: WebSockets.MessageEvent) {
        val parsed = event.content.let { Serialization.json.decodeFromString(inputType, it) }
        this.message.invoke(this, TypedMessageEvent(event.id, parsed))
    }

    override suspend fun disconnect(event: WebSockets.DisconnectEvent) {
        this.disconnect.invoke(this, event)
    }

    suspend fun send(id: String, content: OUTPUT) = WebSockets.send(id, Serialization.json.encodeToString(outputType, content))

}

@LightningServerDsl
inline fun <reified USER, reified INPUT, reified OUTPUT> ServerPath.typedWebsocket(
    summary: String,
    description: String = summary,
    errorCases: List<ApiWebsocket.ErrorCase>,
    noinline connect: suspend ApiWebsocket<USER, INPUT, OUTPUT>.(ApiWebsocket.TypedConnectEvent<USER>) -> Unit = { },
    noinline message: suspend ApiWebsocket<USER, INPUT, OUTPUT>.(ApiWebsocket.TypedMessageEvent<INPUT>) -> Unit = { },
    noinline disconnect: suspend ApiWebsocket<USER, INPUT, OUTPUT>.(WebSockets.DisconnectEvent) -> Unit = {}
): ApiWebsocket<USER, INPUT, OUTPUT> = typedWebsocket(
    authInfo = AuthInfo(),
    inputType = serializerOrContextual(),
    outputType = serializerOrContextual(),
    summary = summary,
            description = description,
            errorCases = errorCases,
            connect = connect,
            message = message,
            disconnect = disconnect,
)

@LightningServerDsl
fun <USER, INPUT, OUTPUT> ServerPath.typedWebsocket(
    authInfo: AuthInfo<USER>,
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,
    summary: String,
    description: String = summary,
    errorCases: List<ApiWebsocket.ErrorCase>,
    connect: suspend ApiWebsocket<USER, INPUT, OUTPUT>.(ApiWebsocket.TypedConnectEvent<USER>) -> Unit = { },
    message: suspend ApiWebsocket<USER, INPUT, OUTPUT>.(ApiWebsocket.TypedMessageEvent<INPUT>) -> Unit = { },
    disconnect: suspend ApiWebsocket<USER, INPUT, OUTPUT>.(WebSockets.DisconnectEvent) -> Unit = {}
): ApiWebsocket<USER, INPUT, OUTPUT> {
    val ws = ApiWebsocket(
        path = this,
        authInfo = authInfo,
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

data class TypedVirtualSocket<INPUT, OUTPUT>(val incoming: ReceiveChannel<OUTPUT>, val send: suspend (INPUT)->Unit)
suspend fun <USER, INPUT, OUTPUT> ApiWebsocket<USER, INPUT, OUTPUT>.test(
    parts: Map<String, String> = mapOf(),
    wildcard: String? = null,
    queryParameters: List<Pair<String, String>> = listOf(),
    headers: HttpHeaders = HttpHeaders.EMPTY,
    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = generalSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "0.0.0.0",
    test: suspend TypedVirtualSocket<INPUT, OUTPUT>.()->Unit
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
                    for(it in incoming) {
                        println("Parsing $it")
                        channel.send(Serialization.json.decodeFromString(outputType, it))
                    }
                }
                test(TypedVirtualSocket<INPUT, OUTPUT>(
                    incoming = channel,
                    send = { send(Serialization.json.encodeToString(inputType, it)) }
                ))
                job.cancelAndJoin()
            }
        },
    )
}