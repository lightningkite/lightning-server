package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.http.HttpRequest
import kotlin.reflect.typeOf

/**
 * Reified information about a user type.
 * The inlined [AuthInfo] function is recommended for creating these, as it will do the hard work for you.
 */
data class AuthInfo<USER>(
    val tryCast: (Any?) -> USER?,
    val type: String? = null,
    val required: Boolean = false,
) {
    fun checker(any: Any?): Boolean = tryCast(any) != null
}

/**
 * Attempts to cast [any] to the [USER] type, throwing an [UnauthorizedException] if auth is required and not available.
 */
@Suppress("UNCHECKED_CAST")
fun <USER> AuthInfo<USER>.cast(any: Any?): USER {
    val casted = tryCast(any)
    if (casted != null) return casted
    if (!required) return null as USER
    throw UnauthorizedException(
        if (any == null) "You need to be authorized to use this." else "You need to be a $type to use this."
    )
}

/**
 * Creates an [AuthInfo] for you.  This is the recommended way to make an [AuthInfo].
 * Creating a [Unit]-based [AuthInfo] indicates that no authentication is performed.
 */
inline fun <reified USER> AuthInfo() =
    if (USER::class == Unit::class) AuthInfo<USER>(tryCast = { Unit as USER }, type = null, required = true)
    else AuthInfo<USER>(
        tryCast = { raw -> raw as? USER },
        type = typeOf<USER>().toString().substringBefore('<').substringAfterLast('.').removeSuffix("?"),
        required = !typeOf<USER>().isMarkedNullable
    )
