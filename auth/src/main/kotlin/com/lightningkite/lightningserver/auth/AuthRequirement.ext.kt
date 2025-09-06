package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.auth.AuthRequirement.Options
import com.lightningkite.lightningserver.ForbiddenException
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.HasId
import kotlin.collections.plus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes


@Suppress("UNCHECKED_CAST")
context(server: ServerRuntime)
public suspend fun <SUBJECT : HasId<*>?> AuthRequirement<SUBJECT>.assert(
    auth: Authentication<*>?
): Authentication<SUBJECT & Any>? =
    if (accepts(auth)) auth?.let { it as Authentication<SUBJECT & Any> }
    else throw ForbiddenException("You do not meet the authorization criteria.")


public typealias AnyId = Comparable<Any?>
public typealias NoAuth = AuthRequirement<HasId<AnyId>?>
public typealias AuthAny = AuthRequirement<HasId<AnyId>>

public val noAuth: AuthRequirement.None = AuthRequirement.None
public val anyAuth: AuthAny = AuthRequirement.Authenticated()

public val recentRootAuth: AuthAny =
    AuthRequirement.Authenticated(
        scopes = RequiredScopes.root, // root access
        maxAge = 10.minutes
    )

public fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> PrincipalType<SUBJECT, ID>.auth(
    scopes: Set<RequiredScope> = RequiredScopes.root,
    maxAge: Duration? = null,
    requirement: (suspend context(ServerRuntime) (Authentication<SUBJECT>) -> Boolean)? = null
): AuthRequirement<SUBJECT> =
    AuthRequirement.AuthenticatedAs(this, scopes, maxAge, requirement)
public fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> PrincipalType<SUBJECT, ID>.auth(
    scope: RequiredScope,
    maxAge: Duration? = null,
    requirement: (suspend context(ServerRuntime) (Authentication<SUBJECT>) -> Boolean)? = null
): AuthRequirement<SUBJECT> =
    AuthRequirement.AuthenticatedAs(this, setOf(scope), maxAge, requirement)


private val <SUBJECT : HasId<*>?> AuthRequirement<SUBJECT>.options: Set<AuthRequirement<SUBJECT>>
    get() = if (this is Options) this.options else setOf(this)

public infix fun <SUBJECT : HasId<*>?> AuthRequirement<SUBJECT>.or(
    other: AuthRequirement<SUBJECT>
): AuthRequirement<SUBJECT> = Options(options + other.options)

public infix fun <SUBJECT : HasId<*>> AuthRequirement<SUBJECT>.or(
    other: AuthRequirement.None
): AuthRequirement<SUBJECT?> = Options(options + other.typed())

public infix fun <SUBJECT : HasId<*>> AuthRequirement.None.or(
    other: AuthRequirement<SUBJECT>
): AuthRequirement<SUBJECT?> = Options(other.options + this.typed())


public val AuthRequirement.Companion.isSuperUser: AuthAny
    get() = AuthRequirement.IsSuperUser
public val AuthRequirement.Companion.isAdmin: AuthAny
    get() = AuthRequirement.IsAdmin
public val AuthRequirement.Companion.isDeveloper: AuthAny
    get() = AuthRequirement.IsDeveloper

context(builder: ServerBuilder)
public var AuthRequirement.Companion.isSuperUser: AuthRequirement<*>
    get() = AuthRequirement.IsSuperUser
    set(value) { builder.extensions[AuthRequirement.IsSuperUser] = value }

context(builder: ServerBuilder)
public var AuthRequirement.Companion.isAdmin: AuthRequirement<*>
    get() = AuthRequirement.IsAdmin
    set(value) { builder.extensions[AuthRequirement.IsAdmin] = value }

context(builder: ServerBuilder)
public var AuthRequirement.Companion.isDeveloper: AuthRequirement<*>
    get() = AuthRequirement.IsDeveloper
    set(value) { builder.extensions[AuthRequirement.IsDeveloper] = value }
