package com.lightningkite.lightningserver.websocket

import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.exceptions.HttpStatusException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.settings.GeneralServerSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.*

object WebSockets {
    val handlers = mutableMapOf<ServerPath, Handler>()
    data class ConnectEvent(
        val path: ServerPath,
        val parts: Map<String, String>,
        val wildcard: String? = null,
        val queryParameters: List<Pair<String, String>>,
        val id: String,
        val headers: HttpHeaders,
        val domain: String,
        val protocol: String,
        val sourceIp: String
    ) {
        fun queryParameter(key: String): String? = queryParameters.find { it.first == key }?.second
    }
    data class MessageEvent(val id: String, val content: String)
    data class DisconnectEvent(val id: String)
    var engineSendMethod: suspend (id: String, content: String)->Unit = { _, _ -> throw IllegalStateException("No engine-defined send method for websockets.") }
    suspend fun send(id: String, content: String): Unit = engineSendMethod(id, content)

    interface Handler {
        suspend fun connect(event: ConnectEvent)
        suspend fun message(event: MessageEvent)
        suspend fun disconnect(event: DisconnectEvent)
    }
}

data class VirtualSocket(val incoming: ReceiveChannel<String>, val send: suspend (String)->Unit)
suspend fun ServerPath.test(
    parts: Map<String, String> = mapOf(),
    wildcard: String? = null,
    queryParameters: List<Pair<String, String>> = listOf(),
    headers: HttpHeaders = HttpHeaders.EMPTY,
    domain: String = GeneralServerSettings.instance.publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = GeneralServerSettings.instance.publicUrl.substringBefore("://"),
    sourceIp: String = "0.0.0.0",
    test: suspend VirtualSocket.()->Unit
) {
    val id = "TEST-${UUID.randomUUID()}"
    val req = WebSockets.ConnectEvent(
        path = this,
        parts = parts,
        wildcard = wildcard,
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
        id = id
    )
    val h = WebSockets.handlers[this]!!

    val channel = Channel<String>(20)
    val oldMethod = WebSockets.engineSendMethod
    WebSockets.engineSendMethod = { id, content ->
        println("$id <-- $content")
        channel.send(content)
    }
    coroutineScope {
        val connectHandle = async {
            println("Connecting $id...")
            h.connect(req)
            println("Connected $id.")
        }
        val testHandle = async {
            test(
                VirtualSocket(
                    incoming = channel,
                    send = {
                        println("$id --> $it")
                        h.message(WebSockets.MessageEvent(id, it))
                    }
                )
            )
            println("Disconnecting $id...")
            h.disconnect(WebSockets.DisconnectEvent(id))
            println("Disconnected $id.")
        }
        listOf(connectHandle, testHandle).awaitAll()
    }
    WebSockets.engineSendMethod = oldMethod
}