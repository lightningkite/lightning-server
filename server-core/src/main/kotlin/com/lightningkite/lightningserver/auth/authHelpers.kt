package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.websocket.WebSockets
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * Gets a user's JWT out of the following sources, prioritized in order:
 * - `jwt` query parameter
 * - `Authorization` header (removing the prefix 'Bearer ')
 * - `Authorization` cookie (removing the prefix 'Bearer ')
 */
fun HttpRequest.jwt(): String? = queryParameter("jwt") ?: headers[HttpHeader.Authorization]?.removePrefix("Bearer ")
?: headers.cookies[HttpHeader.Authorization]?.removePrefix("Bearer ")

/**
 * Gets a user's JWT out of the following sources, prioritized in order:
 * - `jwt` query parameter
 * - `Authorization` header (removing the prefix 'Bearer ')
 * - `Authorization` cookie (removing the prefix 'Bearer ')
 */
fun WebSockets.ConnectEvent.jwt(): String? =
    queryParameter("jwt") ?: headers[HttpHeader.Authorization]?.removePrefix("Bearer ")
    ?: headers.cookies[HttpHeader.Authorization]?.removePrefix("Bearer ")

/**
 * Shortcut for using the [Authentication] handler to get the user.
 */
suspend fun HttpRequest.rawUser(): Any? = Authentication.handler.http(this)

/**
 * Shortcut for using the [Authentication] handler to get the user.
 * Requires the user match the [USER] type or throws a [UnauthorizedException].
 */
suspend inline fun <reified USER> HttpRequest.user(): USER {
    val raw = rawUser()
    raw?.let { it as? USER }?.let { return it }
    try {
        return raw as USER
    } catch (e: Exception) {
        throw UnauthorizedException(
            if (raw == null) "You need to be authorized to use this." else "You need to be a ${USER::class.simpleName} to use this.",
            cause = e
        )
    }
}

/**
 * Shortcut for using the [Authentication] handler to get the user.
 */
suspend fun WebSockets.ConnectEvent.rawUser(): Any? = Authentication.handler.ws(this)
/**
 * Shortcut for using the [Authentication] handler to get the user.
 * Requires the user match the [USER] type or throws a [UnauthorizedException].
 */
suspend inline fun <reified USER> WebSockets.ConnectEvent.user(): USER {
    val raw = rawUser()
    raw?.let { it as? USER }?.let { return it }
    try {
        return raw as USER
    } catch (e: Exception) {
        throw UnauthorizedException(
            if (raw == null) "You need to be authorized to use this." else "You need to be a ${USER::class.simpleName} to use this.",
            cause = e
        )
    }
}
