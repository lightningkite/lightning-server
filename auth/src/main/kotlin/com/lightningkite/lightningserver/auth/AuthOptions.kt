package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.ForbiddenException
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.HasId
import kotlin.time.Duration

@JvmInline
public value class AuthOptions<SUBJECT : HasId<ID>?, ID : Comparable<ID>> internal constructor(
    public val options: Set<AuthenticationRequirement<SUBJECT, ID>>
) {
    public constructor(vararg requirements: AuthenticationRequirement<SUBJECT, ID>) : this(requirements.toSet())

    context(server: ServerRuntime)
    public suspend fun accepts(auth: Authentication<*, *>?): Boolean = options.any { it.accepts(auth) }

    @Suppress("UNCHECKED_CAST")
    context(server: ServerRuntime)
    public suspend fun assert(auth: Authentication<*, *>?): Authentication<SUBJECT & Any, ID>? =
        if (accepts(auth)) auth?.let { it as Authentication<SUBJECT & Any, ID> }
        else throw ForbiddenException("You do not meet the authorization criteria.")

    public infix fun or(other: AuthOptions<SUBJECT, ID>): AuthOptions<SUBJECT, ID> = AuthOptions(options + other.options)
    public infix fun or(requirement: AuthenticationRequirement<SUBJECT, ID>): AuthOptions<SUBJECT, ID> = AuthOptions(options + requirement)

    override fun toString(): String = "AuthOptions(${options.joinToString()})"
}

public typealias AnyId = Comparable<Any?>

public typealias NoAuth = AuthOptions<HasId<AnyId>?, AnyId>
public typealias AuthAny = AuthOptions<HasId<AnyId>, AnyId>

public val noAuth: NoAuth = AuthOptions(AuthenticationRequirement.NoAuthentication)
public val anyAuth: AuthAny = AuthOptions(AuthenticationRequirement.AnyAuthentication)

public fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> PrincipalType<SUBJECT, ID>.auth(
    /**The required scopes. Empty set indicates no requirements and * indicates root access.*/
    scopes: Set<String> = setOf("*"),
    maxAge: Duration? = null,
    requirement: (suspend context(ServerRuntime) (Authentication<SUBJECT, ID>) -> Boolean)? = null
): AuthOptions<SUBJECT, ID> = AuthOptions(
    AuthenticationRequirement.AuthenticatedAs(this, scopes, maxAge, requirement)
)

@Suppress("UNCHECKED_CAST")
@JvmName("orNoAuth")
public infix fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> AuthOptions<SUBJECT, ID>.or(other: NoAuth): AuthOptions<SUBJECT?, ID> =
    AuthOptions(options as Set<AuthenticationRequirement<SUBJECT?, ID>> + AuthenticationRequirement.NoAuthentication as AuthenticationRequirement<SUBJECT?, ID>)

@Suppress("UNCHECKED_CAST")
@JvmName("orOtherAuth")
public infix fun <S1 : HasId<I1>, I1 : Comparable<I1>, S2 : HasId<I2>, I2 : Comparable<I2>>
        AuthOptions<S1, I1>.or(other: AuthOptions<S2, I2>): AuthAny =
    AuthOptions(options as Set<AuthenticationRequirement<HasId<AnyId>, AnyId>> + AuthenticationRequirement.NoAuthentication as AuthenticationRequirement<HasId<AnyId>, AnyId>)

@Suppress("UNCHECKED_CAST")
@JvmName("orAnyAuth")
public infix fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> AuthAny.or(other: AuthOptions<SUBJECT, ID>): AuthAny =
    AuthOptions(options + other.options as Set<AuthenticationRequirement<HasId<AnyId>, AnyId>>)

@Suppress("UNCHECKED_CAST")
@JvmName("anyOrNoAuth")
public infix fun AuthAny.or(other: NoAuth): AuthOptions<HasId<AnyId>?, AnyId> =
    AuthOptions(AuthenticationRequirement.NoAuthentication, AuthenticationRequirement.AnyAuthentication as AuthenticationRequirement<HasId<AnyId>?, AnyId>)