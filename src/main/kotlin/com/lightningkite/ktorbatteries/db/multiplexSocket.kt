package com.lightningkite.ktorbatteries.db

import com.lightningkite.ktorkmongo.MultiplexMessage
import io.ktor.application.*
import io.ktor.http.cio.websocket.*
import io.ktor.routing.*
import io.ktor.websocket.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.set


private data class OpenChannel(val channel: Channel<String>, val job: Job)
fun Route.multiplexWebSocket() {
    val feature = application.feature(JsonWebSockets)
    webSocket {
        val entries = feature.entries
        val myOpenSockets = ConcurrentHashMap<String, OpenChannel>()
        incomingLoop@for(message in incoming) {
            if(message !is Frame.Text) continue
            val text = message.readText()
            if(text == "") {
                send("")
                continue
            }
            val decoded: MultiplexMessage = feature.json.decodeFromString(text)
            when {
                decoded.start -> {
                    val handler = entries[decoded.path]
                    if(handler == null) {
//                        println("Path '${decoded.path}' not found.  Available paths: ${feature.entries.keys.joinToString()}")
//                        send(feature.json.encodeToString(MultiplexMessage(channel = decoded.channel, error = "Path '${decoded.path}' not found.  Available paths: ${feature.entries.keys.joinToString()}")))
                        continue@incomingLoop
                    }
                    val incomingChannel = Channel<String>()
                    myOpenSockets[decoded.channel] = OpenChannel(
                        channel = incomingChannel,
                        job = launch {
                            handler(JsonWebSockets.UntypedSession(
                                call = this@webSocket.call,
                                send = { send(feature.json.encodeToString(MultiplexMessage(channel = decoded.channel, data = it))) },
                                incoming = incomingChannel.consumeAsFlow()
                            ))
                        }
                    )
                    send(feature.json.encodeToString(MultiplexMessage(channel = decoded.channel, path = decoded.path, start = true)))
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
    }
}

fun Route.multiplexWebSocket(path: String) {
    route(path) {
        multiplexWebSocket()
    }
}
