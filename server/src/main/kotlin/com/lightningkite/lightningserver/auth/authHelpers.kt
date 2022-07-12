package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.http.Http
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.serialization.serializerOrContextual
import com.lightningkite.lightningserver.websocket.WebSockets
import kotlinx.serialization.KSerializer
import kotlin.reflect.typeOf


inline fun <reified T> HttpRequest.jwt(jwtSigner: JwtSigner): T? = jwt(jwtSigner, serializerOrContextual())
fun <T> HttpRequest.jwt(jwtSigner: JwtSigner, serializer: KSerializer<T>): T? =
    (headers[HttpHeader.Authorization]?.removePrefix("Bearer ") ?: headers.cookies[HttpHeader.Authorization]?.removePrefix("Bearer "))?.let {
        try {
            jwtSigner.verify<T>(serializer, it)
        } catch(e: UnauthorizedException) {
            throw UnauthorizedException(
                body = e.body,
                headers = {
                    setCookie(HttpHeader.Authorization, "deleted", maxAge = 0)
                },
                cause = e.cause
            )
        }
    }

data class AuthInfo<USER>(
    val checker: suspend (Any?)->USER,
    val type: String? = null,
    val required: Boolean = false,
)
inline fun <reified USER> AuthInfo() = if(USER::class == Unit::class) AuthInfo<USER>(checker = { Unit as USER }, type = null, required = false)
else AuthInfo<USER>(
    checker = { raw ->
        try {
            raw as USER
        } catch (e: Exception) {
            throw UnauthorizedException(
                if (raw == null) "You need to be authorized to use this." else "You need to be a ${USER::class.simpleName} to use this.",
                cause = e
            )
        }
    },
    type = typeOf<USER>().toString().substringBefore('<').substringAfterLast('.').removeSuffix("?"),
    required = !typeOf<USER>().isMarkedNullable
)
typealias TypeCheckOrUnauthorized<USER> = HttpRequest.()->USER

private var authorizationMethodImpl: suspend (HttpRequest) -> Any? = { null }
var Http.authorizationMethod: suspend (HttpRequest) -> Any?
    get() = authorizationMethodImpl
    set(value) {
        authorizationMethodImpl = value
    }

suspend fun HttpRequest.rawUser(): Any? = Http.authorizationMethod(this)
suspend inline fun <reified USER> HttpRequest.user(): USER {
    val raw = Http.authorizationMethod(this)
    raw?.let { it as? USER }?.let { return it }
    try {
        return raw as USER
    } catch(e: Exception) {
        throw UnauthorizedException(
            if(raw == null) "You need to be authorized to use this." else "You need to be a ${USER::class.simpleName} to use this.",
            cause = e
        )
    }
}

private var wsAuthorizationMethodImpl: suspend (WebSockets.ConnectEvent) -> Any? = { null }
var WebSockets.authorizationMethod: suspend (WebSockets.ConnectEvent) -> Any?
    get() = wsAuthorizationMethodImpl
    set(value) {
        wsAuthorizationMethodImpl = value
    }

suspend fun WebSockets.ConnectEvent.rawUser(): Any? = WebSockets.authorizationMethod(this)
suspend inline fun <reified USER> WebSockets.ConnectEvent.user(): USER {
    val raw = WebSockets.authorizationMethod(this)
    raw?.let { it as? USER }?.let { return it }
    try {
        return raw as USER
    } catch(e: Exception) {
        throw UnauthorizedException(
            if(raw == null) "You need to be authorized to use this." else "You need to be a ${USER::class.simpleName} to use this.",
            cause = e
        )
    }
}