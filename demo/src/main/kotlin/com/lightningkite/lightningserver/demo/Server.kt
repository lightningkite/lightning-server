package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.auth.AuthCacheKey
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.auth
import com.lightningkite.lightningserver.auth.fetch
import com.lightningkite.lightningserver.auth.get
import com.lightningkite.lightningserver.auth.isSuperUser
import com.lightningkite.lightningserver.auth.or
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.builder.bind
import com.lightningkite.lightningserver.definition.builder.setting
import com.lightningkite.lightningserver.definition.builder.topic
import com.lightningkite.lightningserver.demo.UserAuthEndpoints.isSuperUser
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.send
import com.lightningkite.lightningserver.serialization.basicMediaTypeCoders
import com.lightningkite.lightningserver.sessions.AuthEndpoints
import com.lightningkite.lightningserver.typed.modelInfo
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.ModelPermissions
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

@Serializable
data class User(
    override val _id: Uuid,
    val superUser: Boolean = false
) : HasId<Uuid> {
    companion object : PrincipalType<User, Uuid> {
        override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
        override val subjectSerializer: KSerializer<User> = serializer()

        context(server: ServerRuntime)
        override suspend fun fetch(id: Uuid): User = User(id)
    }
}


object UserAuthEndpoints : AuthEndpoints<User, Uuid>(
    principal = User,
    database = Server.database,
) {
    context(server: ServerRuntime)
    override suspend fun sessionStaleAfter(subject: User): Duration = 10.minutes

    object IsSuperUserCache : AuthCacheKey<User, Boolean> {
        override val id: String = "isSuperUser"
        override val serializer: KSerializer<Boolean> = Boolean.serializer()

        context(server: ServerRuntime)
        override suspend fun calculate(input: Authentication<User>): Boolean = input.fetch().superUser
    }

    context(_: ServerRuntime)
    suspend fun Authentication<User>.isSuperUser() = get(IsSuperUserCache)
}


object Server : ServerBuilder() {
    init {
        basicMediaTypeCoders()

        AuthRequirement.isSuperUser = User.auth { it.isSuperUser() }
    }


    val database = setting("database", Database.Settings())

    val auth = path.path("user").path("auth") bind UserAuthEndpoints

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

    val model = path.path("model") bind ModelEndpoints
}


@Serializable
data class Model(
    override val _id: Uuid = Uuid.random(),
    val name: String,
    val data: Int,
) : HasId<Uuid>

object ModelEndpoints : ServerBuilder() {
    val info = Server.database.modelInfo(
        auth = User.auth(),
        permissions = { ModelPermissions.allowAll<Model>() },
    )
}