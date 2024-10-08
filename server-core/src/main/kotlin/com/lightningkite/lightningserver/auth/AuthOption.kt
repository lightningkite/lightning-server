package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningdb.Description
import com.lightningkite.lightningdb.HasId
import kotlin.time.Duration
import kotlin.reflect.typeOf
import kotlin.time.Duration.Companion.minutes

class AuthOption(
    val type: AuthType,
    @Description("The required scopes.  Null indicates no special scope is required.  * indicates root access.")
    val scopes: Set<String>? = setOf("*"),
    val maxAge: Duration? = null,
    val limitationDescription: String? = null,
    val additionalRequirement: suspend (RequestAuth<*>) -> Boolean = { true }
) {
}

data class AuthOptions<out USER : HasId<*>?>(val options: Set<AuthOption?>)
operator fun <USER: HasId<*>?> AuthOptions<USER>.plus(
    other: AuthOptions<USER>
) = AuthOptions<USER>(options + other.options)

inline fun <reified USER : HasId<*>> authRequired(
    scopes: Set<String>? = setOf("*"),
    maxAge: Duration? = null,
    limitationDescription: String? = null,
    crossinline additionalRequirement: suspend (RequestAuth<USER>) -> Boolean = { true }
): AuthOptions<USER> {
    val type = typeOf<USER>()
    return AuthOptions<USER>(
        setOf(
            AuthOption(
                AuthType(type), scopes,
                maxAge,
                limitationDescription,
                {
                    @Suppress("UNCHECKED_CAST")
                    additionalRequirement(it as RequestAuth<USER>)
                }
            )
        )
    )
}

inline fun <reified USER : HasId<*>> authOptional(
    scopes: Set<String>? = setOf("*"),
    maxAge: Duration? = null,
    limitationDescription: String? = null,
    crossinline additionalRequirement: suspend (RequestAuth<USER>) -> Boolean = { true }
): AuthOptions<USER?> {
    val type = typeOf<USER>()
    @Suppress("UNCHECKED_CAST")
    return AuthOptions<USER?>(
        setOf(
            AuthOption(
                AuthType(type), scopes,
                maxAge,
                limitationDescription,
                {
                    @Suppress("UNCHECKED_CAST")
                    additionalRequirement(it as RequestAuth<USER>)
                }
            ), null
        )
    )
}

inline fun <reified USER : HasId<*>?> authOptions(
    scopes: Set<String>? = setOf("*"),
    maxAge: Duration? = null,
    limitationDescription: String? = null
): AuthOptions<USER> {
    val type = typeOf<USER>()
    return AuthOptions<USER>(
        setOf(
            AuthOption(
                AuthType(type),
                scopes,
                maxAge,
                limitationDescription
            )
        ) + if(type.isMarkedNullable) setOf(null) else emptySet()
    )
}

val noAuth: AuthOptions<HasId<*>?> = AuthOptions(setOf(null))
val anyAuth: AuthOptions<HasId<*>> = AuthOptions(setOf(AuthOption(AuthType.any, scopes = setOf())))
val anyAuthRoot: AuthOptions<HasId<*>> = AuthOptions(setOf(AuthOption(AuthType.any, maxAge = 10.minutes)))
