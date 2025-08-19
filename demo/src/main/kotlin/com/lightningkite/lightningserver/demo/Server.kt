package com.lightningkite.lightningserver.demo

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.auth
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.auth.or
import com.lightningkite.lightningserver.auth.register
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.builder.bind
import com.lightningkite.lightningserver.definition.builder.topic
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.send
import com.lightningkite.lightningserver.sdk.ClientInterfaceBuilder
import com.lightningkite.lightningserver.sdk.module
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.MediaTypeEncoder
import com.lightningkite.lightningserver.typed.auth
import com.lightningkite.lightningserver.typed.mediaTypeEncoders
import com.lightningkite.lightningserver.websockets.WebSocketClose
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.subscribe
import com.lightningkite.lightningserver.websockets.text
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.HasId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.serializer
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.uuid.Uuid

@Serializable
data class User(
    override val _id: Uuid
) : HasId<Uuid> {
    companion object : PrincipalType<User, Uuid> {
        override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
        override val subjectSerializer: KSerializer<User> = serializer()

        context(server: ServerRuntime)
        override suspend fun fetch(id: Uuid): User = User(id)
    }
}

object Server : ServerBuilder() {
    init { register(User) }

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

    init {
        http.interceptors.register { req, cont ->
            println("Intercepted request: $req")
            cont(req)
        }
    }

    val ping = path.path("ping").post bind HttpHandler {
        val body = it.body?.text()
        println(body)
        topic.send(body ?: "No Body")
        HttpResponse()
    }

    val api = path.path("api").arg<Uuid>("id").post bind ApiHttpHandler(
        summary = "Api Endpoint",
        description = "Api Endpoint",
        authOptions = User.auth(scopes = setOf("api-endpoints"))
    ) { input: Int ->

        auth

        Unit
    }

    val module = path.path("module") bind module(ModelEndpoints)
    val module2 = path.path("module2") bind module(ModelEndpoints, interfaceName = "Module2")
}

object ModelEndpoints : ServerBuilder() {
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

    val rest = path.path("rest") bind Rest(0)
}

class Rest<T>(val item: T) : ClientInterfaceBuilder()