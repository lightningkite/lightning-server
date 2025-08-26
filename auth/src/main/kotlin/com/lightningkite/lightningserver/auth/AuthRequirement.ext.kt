package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.auth.AuthRequirement.Options
import com.lightningkite.lightningserver.ForbiddenException
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.HasId
import kotlin.collections.plus
import kotlin.time.Duration


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

public val noAuth: AuthRequirement.NoAuth = AuthRequirement.NoAuth
public val anyAuth: AuthRequirement.AnyAuth = AuthRequirement.AnyAuth

public fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> PrincipalType<SUBJECT, ID>.auth(
    /**The required scopes. Empty set indicates no requirements and * indicates root access.*/
    scopes: Set<String> = setOf("*"),
    maxAge: Duration? = null,
    requirement: (suspend context(ServerRuntime) (Authentication<SUBJECT>) -> Boolean)? = null
): AuthRequirement<SUBJECT> =
    AuthRequirement.AuthenticatedAs(this, scopes, maxAge, requirement)


private val <SUBJECT : HasId<*>?> AuthRequirement<SUBJECT>.options: Set<AuthRequirement<SUBJECT>>
    get() = if (this is Options) this.options else setOf(this)

public infix fun <SUBJECT : HasId<*>?> AuthRequirement<SUBJECT>.or(
    other: AuthRequirement<SUBJECT>
): AuthRequirement<SUBJECT> = Options(options + other.options)

public infix fun <SUBJECT : HasId<*>> AuthRequirement<SUBJECT>.or(
    other: AuthRequirement.NoAuth
): AuthRequirement<SUBJECT?> = Options(options + other.typed())

public infix fun <SUBJECT : HasId<*>> AuthRequirement.NoAuth.or(
    other: Options<SUBJECT>
): AuthRequirement<SUBJECT?> = Options(other.options + this.typed())


public val Options.Companion.isSuperUser: AuthAny
    get() = AuthRequirement.IsSuperUser
public val Options.Companion.isAdmin: AuthAny
    get() = AuthRequirement.IsAdmin
public val Options.Companion.isDeveloper: AuthAny
    get() = AuthRequirement.IsDeveloper

context(builder: ServerBuilder)
public var Options.Companion.isSuperUser: AuthAny
    get() = AuthRequirement.IsSuperUser
    set(value) { builder.extensions[AuthRequirement.IsSuperUser] = value }

context(builder: ServerBuilder)
public var Options.Companion.isAdmin: AuthAny
    get() = AuthRequirement.IsAdmin
    set(value) { builder.extensions[AuthRequirement.IsAdmin] = value }

context(builder: ServerBuilder)
public var Options.Companion.isDeveloper: AuthAny
    get() = AuthRequirement.IsDeveloper
    set(value) { builder.extensions[AuthRequirement.IsDeveloper] = value }
