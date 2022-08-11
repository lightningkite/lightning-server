package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.http.HttpRequest
import kotlin.reflect.typeOf

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