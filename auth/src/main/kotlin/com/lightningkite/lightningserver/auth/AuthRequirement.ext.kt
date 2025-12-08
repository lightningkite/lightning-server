package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.auth.AuthRequirement.Options
import com.lightningkite.lightningserver.ForbiddenException
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.HasId
import kotlin.collections.plus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

context(server: ServerRuntime)
public suspend fun AuthRequirement<*>.accepts(auth: Authentication<*>?): Boolean =
    when (check(auth)) {
        is AuthRequirement.Result.Rejected -> false
        is AuthRequirement.Result.Accepted -> true
    }

/**
 * Asserts that the given [Authentication] will be accepted by this [AuthRequirement].
 * If the authentication is not accepted, a [ForbiddenException] will be thrown.
 * */
context(server: ServerRuntime)
public suspend fun <SUBJECT : HasId<*>?> AuthRequirement<SUBJECT>.assert(
    auth: Authentication<*>?
): Authentication<SUBJECT & Any>? =
    when (val r = check(auth)) {
        is AuthRequirement.Result.Rejected -> throw ForbiddenException(
            """
                You do not meet the authorization criteria
                Requirement: ${this.naturalLanguage()}
                Failed On: ${r.reason}
            """.trimIndent()
        )
        is AuthRequirement.Result.Accepted<SUBJECT> -> r.auth
    }

public fun <SUBJECT : HasId<*>?> AuthRequirement<SUBJECT>.subscope(subscope: Subscope): AuthRequirement<SUBJECT> = subscope(listOf(subscope))


public val noAuth: AuthRequirement.None get() = AuthRequirement.None

public val anyAuth: AuthRequirement.Authenticated = AuthRequirement.Authenticated(scopes = emptySet())

public val recentRootAuth: AuthRequirement.Authenticated =
    AuthRequirement.Authenticated(
        scopes = setOf(RequiredScope.root), // root access
        maxAge = 10.minutes
    )

/**
 * Creates an [AuthRequirement] that requires authentication of this [SUBJECT] type,
 * along with the other specified requirements.
 * */
public fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> PrincipalType<SUBJECT, ID>.require(
    scopes: Set<RequiredScope>,
    maxAge: Duration? = null,
    requirement: (suspend context(ServerRuntime) (Authentication<SUBJECT>) -> Boolean)? = null
): AuthRequirement<SUBJECT> =
    AuthRequirement.AuthenticatedAs(this, scopes, maxAge, requirement)

/**
 * Creates an [AuthRequirement] that requires authentication of this [SUBJECT] type,
 * along with the other specified requirements.
 * */
public fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> PrincipalType<SUBJECT, ID>.require(
    scope: RequiredScope = RequiredScope.root,
    maxAge: Duration? = null,
    requirement: (suspend context(ServerRuntime) (Authentication<SUBJECT>) -> Boolean)? = null
): AuthRequirement<SUBJECT> =
    AuthRequirement.AuthenticatedAs(this, setOf(scope), maxAge, requirement)


public fun <T : HasId<*>?> AuthRequirement<T>.options(): Set<AuthRequirement<T>> =
    if (this is Options) options else setOf(this)

public infix fun <SUBJECT : HasId<*>?> AuthRequirement<SUBJECT>.or(
    other: AuthRequirement<SUBJECT>
): AuthRequirement<SUBJECT> =
    Options(options() + other.options())


public val AuthRequirement.Companion.isSuperUser: AuthRequirement<HasId<*>>
    get() = AuthRequirement.IsSuperUser
public val AuthRequirement.Companion.isAdmin: AuthRequirement<HasId<*>>
    get() = AuthRequirement.IsAdmin
public val AuthRequirement.Companion.isDeveloper: AuthRequirement<HasId<*>>
    get() = AuthRequirement.IsDeveloper

context(builder: ServerBuilder)
public var AuthRequirement.Companion.isSuperUser: AuthRequirement<HasId<*>>
    get() = AuthRequirement.IsSuperUser
    set(value) { builder.extensions[AuthRequirement.IsSuperUser] = value }

context(builder: ServerBuilder)
public var AuthRequirement.Companion.isAdmin: AuthRequirement<HasId<*>>
    get() = AuthRequirement.IsAdmin
    set(value) { builder.extensions[AuthRequirement.IsAdmin] = value }

context(builder: ServerBuilder)
public var AuthRequirement.Companion.isDeveloper: AuthRequirement<HasId<*>>
    get() = AuthRequirement.IsDeveloper
    set(value) { builder.extensions[AuthRequirement.IsDeveloper] = value }



context(_: ServerRuntime)
public fun AuthRequirement<*>.naturalLanguage(markdown: Boolean = false): String = when (this) {
    is AuthRequirement.Options -> options.joinToString(if (markdown) " *or* " else " or ") { it.naturalLanguage(markdown) }
    is AuthRequirement.AuthSetting -> setting()?.let { "$this (${it.naturalLanguage(markdown)})" } ?: this.toString()
    else -> this.toString()
}