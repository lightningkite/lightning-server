package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.http.HttpRequest
import kotlin.reflect.typeOf

data class AuthInfo<USER>(
    val checker: (Any?)->Boolean,
    val type: String? = null,
    val required: Boolean = false,
)
@Suppress("UNCHECKED_CAST")
fun <USER> AuthInfo<USER>.cast(any: Any?): USER {
    println("Trying to cast $any to $type")
    if(checker(any)) return any as USER
    if(!required) return null as USER
    throw UnauthorizedException(
        if (any == null) "You need to be authorized to use this." else "You need to be a ${type} to use this."
    )
}
@Suppress("UNCHECKED_CAST")
fun <USER> AuthInfo<USER>.tryCast(any: Any?): USER? {
    println("Trying to cast $any to $type")
    if(checker(any)) return any as USER
    return null
}

inline fun <reified USER> AuthInfo() = if(USER::class == Unit::class) AuthInfo<USER>(checker = { Unit is USER }, type = null, required = false)
else AuthInfo<USER>(
    checker = { raw -> raw is USER },
    type = typeOf<USER>().toString().substringBefore('<').substringAfterLast('.').removeSuffix("?"),
    required = !typeOf<USER>().isMarkedNullable
)
typealias TypeCheckOrUnauthorized<USER> = HttpRequest.()->USER