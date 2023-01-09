package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.http.HttpRequest
import kotlin.reflect.typeOf

data class AuthInfo<USER>(
    val tryCast: (Any?)->USER?,
    val type: String? = null,
    val required: Boolean = false,
) {
    fun checker(any: Any?): Boolean = tryCast(any) != null
}
@Suppress("UNCHECKED_CAST")
fun <USER> AuthInfo<USER>.cast(any: Any?): USER {
    val casted = tryCast(any)
    if(casted != null) return casted
    if(!required) return null as USER
    throw UnauthorizedException(
        if (any == null) "You need to be authorized to use this." else "You need to be a $type to use this."
    )
}
inline fun <reified USER> AuthInfo() = if(USER::class == Unit::class) AuthInfo<USER>(tryCast = { Unit as USER }, type = null, required = true)
else AuthInfo<USER>(
    tryCast = { raw -> raw as? USER },
    type = typeOf<USER>().toString().substringBefore('<').substringAfterLast('.').removeSuffix("?"),
    required = !typeOf<USER>().isMarkedNullable
)
typealias TypeCheckOrUnauthorized<USER> = HttpRequest.()->USER