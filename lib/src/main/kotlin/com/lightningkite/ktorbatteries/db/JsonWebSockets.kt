package com.lightningkite.ktorbatteries.db

import com.lightningkite.ktorbatteries.routes.fullPath
import io.ktor.application.*
import io.ktor.http.cio.websocket.*
import io.ktor.routing.*
import io.ktor.util.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

class JsonWebSockets {

    class Session<SEND, RECEIVE>(
        val call: ApplicationCall,
        val send: suspend (SEND)->Unit,
        val incoming: Flow<RECEIVE>
    )
    class UntypedSession(
        val call: ApplicationCall,
        val send: suspend (String)->Unit,
        val incoming: Flow<String>
    )

    val entries = HashMap<String, suspend UntypedSession.() -> Unit>()
    var json: Json = Json

    companion object Feature: ApplicationFeature<ApplicationCallPipeline, JsonWebSockets, JsonWebSockets> {
        override val key: AttributeKey<JsonWebSockets> = AttributeKey("JsonWebSockets")

        override fun install(
            pipeline: ApplicationCallPipeline,
            configure: JsonWebSockets.() -> Unit
        ): JsonWebSockets = JsonWebSockets().apply(configure)
    }
}

fun <SEND, RECEIVE> Route.jsonWebSocket(sendSerializer: KSerializer<SEND>, receiveSerializer: KSerializer<RECEIVE>, handler: suspend JsonWebSockets.Session<SEND, RECEIVE>.()->Unit) {
    val feature = application.feature(JsonWebSockets)
    webSocket {
        handler(
            JsonWebSockets.Session(
            call = this.call,
            send = { send(feature.json.encodeToString(sendSerializer, it)) },
            incoming = incoming.consumeAsFlow()
                .mapNotNull { it as? Frame.Text }
                .mapNotNull {
                    val text = it.readText()
                    if(text == "") {
                        send("")
                        null
                    } else {
                        feature.json.decodeFromString(receiveSerializer, text)
                    }
                }
        ))
    }
    @Suppress("UNCHECKED_CAST")
    feature.entries[this.fullPath] = {
        handler(
            JsonWebSockets.Session(
            call = this.call,
            send = { send(feature.json.encodeToString(sendSerializer, it)) },
            incoming = incoming.map { feature.json.decodeFromString(receiveSerializer, it) }
        ))
    }
}
inline fun <reified SEND, reified RECEIVE> Route.jsonWebSocket(noinline handler: suspend JsonWebSockets.Session<SEND, RECEIVE>.()->Unit) {
    val feature = application.feature(JsonWebSockets)
    jsonWebSocket<SEND, RECEIVE>(feature.json.serializersModule.serializer(), feature.json.serializersModule.serializer(), handler)
}

inline fun <reified SEND, reified RECEIVE> Route.jsonWebSocket(path: String, noinline handler: suspend JsonWebSockets.Session<SEND, RECEIVE>.()->Unit) {
    route(path) {
        jsonWebSocket(handler)
    }
}