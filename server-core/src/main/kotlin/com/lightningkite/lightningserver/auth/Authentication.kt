package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.SetOnce
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.websocket.WebSockets

/**
 * Rules for authenticating requests are defined in this object.
 */
object Authentication {
    private val handlerSetOnce: SetOnce<Handler<*>> = SetOnce {
        object : Handler<Unit> {
            override suspend fun http(request: HttpRequest): Unit? = null
            override suspend fun ws(request: WebSockets.ConnectEvent): Unit? = null
            override suspend fun idStringToUser(id: String) = Unit
            override fun userToIdString(user: Unit): String = ""
        }
    }
    val handlerSet: Boolean get() = handlerSetOnce.set

    /**
     * The rules for authenticating requests.
     * Can only be set once.
     */
    var handler: Handler<*> by handlerSetOnce

    /**
     * Rules for authenticating requests
     */
    interface Handler<USER> {
        /**
         * @return the logged in [USER] based on the HTTP [request]
         */
        suspend fun http(request: HttpRequest): USER?
        /**
         * @return the logged in [USER] based on the WS [WebSockets.ConnectEvent]
         */
        suspend fun ws(request: WebSockets.ConnectEvent): USER?
        /**
         * @return Some ID string that can be used to retrieve the full user object later.
         */
        fun userToIdString(user: USER): String

        /**
         * @return A user based on the ID string created in [userToIdString].
         */
        suspend fun idStringToUser(id: String): USER
    }
}

@Deprecated(
    "Use 'Authentication' instead",
    ReplaceWith("Authentication", "com.lightningkite.lightningserver.auth.Authentication")
)
object Authorization {
    @Suppress("UNCHECKED_CAST")
    var handler: Handler<*>
        get() = object : Handler<Any?>, Authentication.Handler<Any?> by (handler as Authentication.Handler<Any?>) {}
        set(value) {
            Authentication.handler = value
        }

    interface Handler<USER> : Authentication.Handler<USER>
}
