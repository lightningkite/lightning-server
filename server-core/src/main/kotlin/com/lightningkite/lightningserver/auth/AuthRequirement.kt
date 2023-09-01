package com.lightningkite.lightningserver.auth

import kotlin.reflect.typeOf

/**
 * Creates an [AuthRequirement] for you.  This is the recommended way to make an [AuthRequirement].
 * Creating a [Unit]-based [AuthRequirement] indicates that no authentication is performed.
 */
@Suppress("UNCHECKED_CAST")
inline fun <reified USER: Any> AuthType(): AuthType {
    if (USER::class == Unit::class) return AuthType.none
    val type = typeOf<USER>()
    return AuthType(type)
}



/**
 * Reified information about a user type.
 */
class AuthRequirement<USER>(
    val type: AuthType,
    val required: Boolean,
) {
    companion object {
        val none = AuthRequirement<Unit>(AuthType.none, false)
    }
}

/**
 * Creates an [AuthRequirement] for you.  This is the recommended way to make an [AuthRequirement].
 * Creating a [Unit]-based [AuthRequirement] indicates that no authentication is performed.
 */
@Suppress("UNCHECKED_CAST")
inline fun <reified USER> AuthRequirement(): AuthRequirement<USER> {
    if (USER::class == Unit::class) return AuthRequirement.none as AuthRequirement<USER>
    val type = typeOf<USER>()
    if(type.isMarkedNullable) return AuthRequirement(AuthType(type), false)
    else return AuthRequirement(AuthType(type), true)
}

