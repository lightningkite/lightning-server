package com.lightningkite.ktorbatteries.typed

import com.lightningkite.ktorbatteries.routes.fullPath
import com.lightningkite.ktorbatteries.serialization.Serialization
import com.lightningkite.ktorkmongo.MultiplexMessage
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.serializer
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.set
import kotlin.reflect.jvm.jvmErasure


private data class OpenChannel(val channel: Channel<String>, val job: Job)

fun Route.multiplexWebSocket(path: String = "") {
    webSocket(path = path) {
        val user = call.principal<Principal>()
        val myOpenSockets = ConcurrentHashMap<String, OpenChannel>()
        try {
            incomingLoop@ for (message in incoming) {
                if (message is Frame.Close) break@incomingLoop
                if (message !is Frame.Text) continue
                val text = message.readText()
                if (text == "") {
                    send("")
                    continue
                }
                val decoded: MultiplexMessage = Serialization.json.decodeFromString(text)
                when {
                    decoded.start -> {
                        @Suppress("UNCHECKED_CAST") val apiWebsocket = ApiWebsocket.known.find { it.route.fullPath == decoded.path } as? ApiWebsocket<Principal, Any?, Any?>
                            ?: continue@incomingLoop
                        if (apiWebsocket.userType != null && !apiWebsocket.userType.jvmErasure.isInstance(user)) continue@incomingLoop
                        val incomingChannel = Channel<String>()
                        val outSerializer = Serialization.json.serializersModule.serializer(apiWebsocket.outputType)
                        val inSerializer = Serialization.json.serializersModule.serializer(apiWebsocket.inputType)
                        myOpenSockets[decoded.channel] = OpenChannel(
                            channel = incomingChannel,
                            job = launch {
                                apiWebsocket.implementation(
                                    ApiWebsocket.Session(
                                        send = {
                                            send(
                                                Serialization.json.encodeToString(
                                                    MultiplexMessage(
                                                        channel = decoded.channel,
                                                        data = Serialization.json.encodeToString(outSerializer, it)
                                                    )
                                                )
                                            )
                                        },
                                        incoming = incomingChannel.consumeAsFlow().map {
                                            Serialization.json.decodeFromString(inSerializer, it)
                                        }
                                    ),
                                    user
                                )
                            }
                        )
                        send(
                            Serialization.json.encodeToString(
                                MultiplexMessage(
                                    channel = decoded.channel,
                                    path = decoded.path,
                                    start = true
                                )
                            )
                        )
                    }
                    decoded.end -> {
                        val open = myOpenSockets.remove(decoded.channel) ?: continue
                        open.job.cancel()
                    }
                    decoded.data != null -> {
                        val open = myOpenSockets[decoded.channel] ?: continue
                        open.channel.send(decoded.data!!)
                    }
                }
            }
        } finally {
            for (value in myOpenSockets.values) {
                value.job.cancel()
                value.channel.close()
            }
            this.close()
        }
    }
}

