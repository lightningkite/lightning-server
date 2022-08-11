package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.websocket.WebSockets

object Authorization {
    var handler: Handler<*> = object: Handler<Unit> {
        override suspend fun http(request: HttpRequest): Unit? = null
        override suspend fun ws(request: WebSockets.ConnectEvent): Unit? = null
        override suspend fun idStringToUser(id: String) = Unit
        override fun userToIdString(user: Unit): String = ""
    }

    interface Handler<USER> {
        suspend fun http(request: HttpRequest): USER?
        suspend fun ws(request: WebSockets.ConnectEvent): USER?
        fun userToIdString(user: USER): String
        suspend fun idStringToUser(id: String): USER
    }
}