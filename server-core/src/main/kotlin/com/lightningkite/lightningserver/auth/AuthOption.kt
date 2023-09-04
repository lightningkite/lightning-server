package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningdb.Description
import java.time.Duration
import kotlin.reflect.typeOf

class AuthOption(
    val type: AuthType,
    @Description("The required scopes.  Null indicates root access.")
    val scopes: Set<String>? = null,
    val maxAge: Duration? = null,
    val limitationDescription: String? = null,
    val additionalRequirement: suspend (RequestAuth<*>)->Boolean = {true}
) {
}

typealias AuthOptions = Set<AuthOption?>

inline fun <reified T> authOptions(
    scopes: Set<String>? = null,
    maxAge: Duration? = null,
    limitationDescription: String? = null,
): Set<AuthOption?> {
    if (T::class == Unit::class) return setOf(null)
    val type = typeOf<T>()
    return if (type.isMarkedNullable) setOf(
        AuthOption(
            AuthType(type), scopes,
            maxAge,
            limitationDescription
        ), null
    )
    else setOf(
        AuthOption(
            AuthType(type), scopes,
            maxAge,
            limitationDescription
        )
    )
}
