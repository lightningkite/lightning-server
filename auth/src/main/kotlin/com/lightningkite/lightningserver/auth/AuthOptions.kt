package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.HasId
import kotlin.time.Duration

public typealias AuthOptions = Set<AuthenticationRequirement>

context(server: ServerRuntime)
public suspend fun AuthOptions.accepts(auth: Authentication<*, *>?): Boolean = any { it.accepts(auth) }

public infix fun AuthOptions.or(other: AuthOptions): AuthOptions = this + other
public infix fun AuthOptions.or(requirement: AuthenticationRequirement): AuthOptions = this + requirement

public val noAuth: AuthOptions = setOf(AuthenticationRequirement.NoAuthentication)
public val anyAuth: AuthOptions = setOf(AuthenticationRequirement.AnyAuthentication)

public fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> PrincipalType<SUBJECT, ID>.auth(
    /**The required scopes. Empty set indicates no requirements and * indicates root access.*/
    scopes: Set<String> = setOf("*"),
    maxAge: Duration? = null,
    requirement: (suspend context(ServerRuntime) (Authentication<SUBJECT, ID>) -> Boolean)? = null
): AuthOptions = setOf(
    AuthenticationRequirement.AuthenticatedAs(this, scopes, maxAge, requirement)
)