package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.websocket.WebSockets
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.reflect.typeOf


inline fun <reified T> HttpRequest.jwt(jwtSigner: JwtSigner): T? = jwt(jwtSigner, Serialization.module.serializer())
fun <T> HttpRequest.jwt(jwtSigner: JwtSigner, serializer: KSerializer<T>): T? =
    (headers[HttpHeader.Authorization]?.removePrefix("Bearer ") ?: headers.cookies[HttpHeader.Authorization]?.removePrefix("Bearer "))?.let {
        try {
            jwtSigner.verify(serializer, it)
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

inline fun <reified T> WebSockets.ConnectEvent.jwt(jwtSigner: JwtSigner): T? = jwt(jwtSigner, Serialization.module.serializer())
fun <T> WebSockets.ConnectEvent.jwt(jwtSigner: JwtSigner, serializer: KSerializer<T>): T? =
    (queryParameter("jwt") ?: headers[HttpHeader.Authorization]?.removePrefix("Bearer ") ?: headers.cookies[HttpHeader.Authorization]?.removePrefix("Bearer "))?.let {
        try {
            jwtSigner.verify(serializer, it)
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

suspend fun HttpRequest.rawUser(): Any? = Authorization.handler.http(this)
suspend inline fun <reified USER> HttpRequest.user(): USER {
    val raw = Authorization.handler.http(this)
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

suspend fun WebSockets.ConnectEvent.rawUser(): Any? = Authorization.handler.ws(this)
suspend inline fun <reified USER> WebSockets.ConnectEvent.user(): USER {
    val raw = Authorization.handler.ws(this)
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