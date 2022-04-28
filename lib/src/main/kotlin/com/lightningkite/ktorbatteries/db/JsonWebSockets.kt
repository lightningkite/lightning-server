package com.lightningkite.ktorbatteries.db

import com.lightningkite.ktorbatteries.routes.fullPath
import com.lightningkite.ktorbatteries.serialization.Serialization
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.util.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

class JsonWebSocketSession<SEND, RECEIVE>(
    val call: ApplicationCall,
    val send: suspend (SEND) -> Unit,
    val incoming: Flow<RECEIVE>
)

class JsonWebSocketUntypedSession(
    val call: ApplicationCall,
    val send: suspend (String) -> Unit,
    val incoming: Flow<String>
)

private val jsonWebSocketEntriesAttr = AttributeKey<MutableMap<String, suspend JsonWebSocketUntypedSession.() -> Unit>>("jsonWebSocketEntries")
val Application.jsonWebSocketEntries: MutableMap<String, suspend JsonWebSocketUntypedSession.() -> Unit>
    get() = this.attributes.getOrNull(jsonWebSocketEntriesAttr) ?: run {
        val map = HashMap<String, suspend JsonWebSocketUntypedSession.() -> Unit>()
        this.attributes.put(jsonWebSocketEntriesAttr, map)
        return@run map
    }


fun <SEND, RECEIVE> Route.jsonWebSocket(
    sendSerializer: KSerializer<SEND>,
    receiveSerializer: KSerializer<RECEIVE>,
    handler: suspend JsonWebSocketSession<SEND, RECEIVE>.() -> Unit
) {
    webSocket {
        handler(
            JsonWebSocketSession(
                call = this.call,
                send = { send(Serialization.json.encodeToString(sendSerializer, it)) },
                incoming = incoming.consumeAsFlow()
                    .mapNotNull { it as? Frame.Text }
                    .mapNotNull {
                        val text = it.readText()
                        if (text == "") {
                            send("")
                            null
                        } else {
                            Serialization.json.decodeFromString(receiveSerializer, text)
                        }
                    }
            ))
    }
    @Suppress("UNCHECKED_CAST")
    application.jsonWebSocketEntries[this.fullPath] = {
        handler(
            JsonWebSocketSession(
                call = this.call,
                send = { send(Serialization.json.encodeToString(sendSerializer, it)) },
                incoming = incoming.map { Serialization.json.decodeFromString(receiveSerializer, it) }
            ))
    }
}

inline fun <reified SEND, reified RECEIVE> Route.jsonWebSocket(noinline handler: suspend JsonWebSocketSession<SEND, RECEIVE>.() -> Unit) {
    jsonWebSocket<SEND, RECEIVE>(
        Serialization.json.serializersModule.serializer(),
        Serialization.json.serializersModule.serializer(),
        handler
    )
}

inline fun <reified SEND, reified RECEIVE> Route.jsonWebSocket(
    path: String,
    noinline handler: suspend JsonWebSocketSession<SEND, RECEIVE>.() -> Unit
) {
    route(path) {
        jsonWebSocket(handler)
    }
}