package com.lightningkite.lightningserver.demo

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.builder.bind
import com.lightningkite.lightningserver.definition.builder.topic
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.send
import com.lightningkite.lightningserver.typed.MediaTypeEncoder
import com.lightningkite.lightningserver.typed.mediaTypeEncoders
import com.lightningkite.lightningserver.websockets.WebSocketClose
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.subscribe
import com.lightningkite.lightningserver.websockets.text
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.serializer

object Server : ServerBuilder() {
    val index = path.get bind HttpHandler {
        HttpResponse.plainText("Ktor Test Success")
    }
    val topic = path.topic(String.serializer())

    val websocket = path bind WebSocketHandler(
        storageSerializer = Unit.serializer(),
        willConnect = { Unit },
        didConnect = { subscribe(topic) },
        topicHandlers = {
            topic bind {
                println("Topic hit!")
                send(WebSocketFrame(it.value))
            }
        },
        messageFromClient = { frame ->
            if (frame is WebSocketFrame.Text && frame.text == "close") {
                close(WebSocketClose.NORMAL)
            } else {
                send(frame)  // Mirror
            }
        },
        disconnect = {}
    )

    val ping = path.path("ping").post bind HttpHandler {
        val body = it.body?.text()
        println(body)
        topic.send(body ?: "No Body")
        HttpResponse()
    }

    val module = path.path("module") bind Module
    val module2 = path.path("module2") bind Module
}

object Module : ServerBuilder() {
    val index = path.get bind HttpHandler {
        HttpResponse.plainText("Module hit")
    }

    val topic = path.topic(String.serializer())

    init {
        mediaTypeEncoders.register(
            object : MediaTypeEncoder {
                override val mediaType: MediaType = MediaType.Text.Plain

                override suspend fun <T> invoke(
                    mediaType: MediaType,
                    serializer: SerializationStrategy<T>,
                    value: T
                ): TypedData = TODO()
            }
        )
    }

    val websocket = path bind WebSocketHandler(
        storageSerializer = Unit.serializer(),
        willConnect = { Unit },
        didConnect = { subscribe(topic); subscribe(Server.topic) },
        topicHandlers = {
            topic bind {
                println("Module Topic hit!")
                send(WebSocketFrame("Module" + it.value))
            }
            Server.topic bind {
                println("Server Topic hit!")
                send(WebSocketFrame("Server" + it.value))
            }
        },
        messageFromClient = { frame ->
            if (frame is WebSocketFrame.Text && frame.text == "close") {
                close(WebSocketClose.NORMAL)
            } else {
                send(frame)  // Mirror
            }
        },
        disconnect = {}
    )
}