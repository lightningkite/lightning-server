package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.ForbiddenException
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.HasId
import kotlin.time.Duration

@JvmInline
public value class AuthOptions<SUBJECT : HasId<ID>?, ID : Comparable<ID>> internal constructor(
    public val options: Set<AuthRequirement<SUBJECT, ID>>
) : AuthRequirement<SUBJECT, ID> {
    public constructor(vararg requirements: AuthRequirement<SUBJECT, ID>) : this(requirements.toSet())

    context(server: ServerRuntime)
    override suspend fun accepts(auth: Authentication<*, *>?): Boolean = options.any { it.accepts(auth) }

    @Suppress("UNCHECKED_CAST")
    context(server: ServerRuntime)
    public suspend fun assert(auth: Authentication<*, *>?): Authentication<SUBJECT & Any, ID>? =
        if (accepts(auth)) auth?.let { it as Authentication<SUBJECT & Any, ID> }
        else throw ForbiddenException("You do not meet the authorization criteria.")

    public infix fun or(other: AuthOptions<SUBJECT, ID>): AuthOptions<SUBJECT, ID> = AuthOptions(options + other.options)
    public infix fun or(requirement: AuthRequirement<SUBJECT, ID>): AuthOptions<SUBJECT, ID> = AuthOptions(options + requirement)

    override fun toString(): String = "AuthOptions(${options.joinToString()})"

    public companion object;
}

public typealias AnyId = Comparable<Any?>

public typealias NoAuth = AuthOptions<HasId<AnyId>?, AnyId>
public typealias AuthAny = AuthOptions<HasId<AnyId>, AnyId>

public val noAuth: NoAuth = AuthOptions(AuthRequirement.NoAuth)
public val anyAuth: AuthAny = AuthOptions(AuthRequirement.AnyAuth)
public val recentRootAuth: AuthAny = AuthOptions(AuthRequirement.RecentRootAuth)


public fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> PrincipalType<SUBJECT, ID>.auth(
    /**The required scopes. Empty set indicates no requirements and * indicates root access.*/
    scopes: Set<String> = setOf("*"),
    maxAge: Duration? = null,
    requirement: (suspend context(ServerRuntime) (Authentication<SUBJECT, ID>) -> Boolean)? = null
): AuthOptions<SUBJECT, ID> = AuthOptions(
    AuthRequirement.AuthenticatedAs(this, scopes, maxAge, requirement)
)

@Suppress("UNCHECKED_CAST")
@JvmName("orNoAuth")
public infix fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> AuthOptions<SUBJECT, ID>.or(other: NoAuth): AuthOptions<SUBJECT?, ID> =
    AuthOptions(options as Set<AuthRequirement<SUBJECT?, ID>> + AuthRequirement.NoAuth as AuthRequirement<SUBJECT?, ID>)

@Suppress("UNCHECKED_CAST")
@JvmName("orOtherAuth")
public infix fun <S1 : HasId<I1>, I1 : Comparable<I1>, S2 : HasId<I2>, I2 : Comparable<I2>>
        AuthOptions<S1, I1>.or(other: AuthOptions<S2, I2>): AuthAny =
    AuthOptions(options as Set<AuthRequirement<HasId<AnyId>, AnyId>> + AuthRequirement.NoAuth as AuthRequirement<HasId<AnyId>, AnyId>)

@Suppress("UNCHECKED_CAST")
@JvmName("orAnyAuth")
public infix fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> AuthAny.or(other: AuthOptions<SUBJECT, ID>): AuthAny =
    AuthOptions(options + other.options as Set<AuthRequirement<HasId<AnyId>, AnyId>>)

@Suppress("UNCHECKED_CAST")
@JvmName("anyOrNoAuth")
public infix fun AuthAny.or(other: NoAuth): AuthOptions<HasId<AnyId>?, AnyId> =
    AuthOptions(AuthRequirement.NoAuth, AuthRequirement.AnyAuth as AuthRequirement<HasId<AnyId>?, AnyId>)



@Suppress("UNCHECKED_CAST")
private val AuthAny.untyped: AuthOptions<HasId<*>, *> get() = this as AuthOptions<HasId<*>, *>

public var ServerBuilder.isAdmin: AuthOptions<HasId<*>, *>
    get() = AuthOptions(AuthRequirement.IsAdmin).untyped
    set(value) { extensions[AuthRequirement.IsAdmin] = value }

public val AuthOptions.Companion.isAdmin: AuthAny
    get() = AuthOptions(AuthRequirement.IsAdmin)


public var ServerBuilder.isDeveloper: AuthOptions<HasId<*>, *>
    get() = AuthOptions(AuthRequirement.IsDeveloper).untyped
    set(value) { extensions[AuthRequirement.IsDeveloper] = value }

public val AuthOptions.Companion.isDeveloper: AuthAny
    get() = AuthOptions(AuthRequirement.IsDeveloper)


public var ServerBuilder.isSuperUser: AuthOptions<HasId<*>, *>
    get() = AuthOptions(AuthRequirement.IsSuperUser).untyped
    set(value) { extensions[AuthRequirement.IsSuperUser] = value }

public val AuthOptions.Companion.isSuperUser: AuthAny
    get() = AuthOptions(AuthRequirement.IsSuperUser)
