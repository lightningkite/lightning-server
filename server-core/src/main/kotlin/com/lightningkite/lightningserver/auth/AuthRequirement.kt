package com.lightningkite.lightningserver.auth

import kotlin.reflect.KClassifier
import kotlin.reflect.KType
import kotlin.reflect.KTypeProjection
import kotlin.reflect.full.withNullability
import kotlin.reflect.typeOf

class AuthType(
    val classifier: KClassifier?,
    val arguments: List<AuthType?>,
) {
    val subject: Authentication.SubjectType? get() = Authentication.methods(this).asSequence().mapNotNull { it.subjectType ?: it.defersTo?.subject }.firstOrNull()
    override fun toString(): String = buildString {
        append(classifier.toString())
        if(arguments.isNotEmpty()) append(arguments.joinToString(", ", "<", ">"))
    }
    constructor(type: KType):this(
        classifier = type.classifier,
        arguments = type.arguments.map { it.type?.let { AuthType(it) } }
    )
    companion object {
        val none = AuthType(Unit::class, listOf())
    }
    override fun hashCode(): Int = this.classifier.hashCode() * 31 + arguments.hashCode()
    override fun equals(other: Any?): Boolean = other is AuthType && this.classifier == other.classifier && this.arguments == other.arguments
}
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

