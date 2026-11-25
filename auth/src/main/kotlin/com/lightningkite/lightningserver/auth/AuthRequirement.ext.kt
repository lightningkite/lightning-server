package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.ForbiddenException
import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.UnauthorizedException
import com.lightningkite.lightningserver.auth.AuthRequirement.Options
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.data.get
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.set
import com.lightningkite.lightningserver.runtime.Engine
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.HasId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

context(server: ServerRuntime)
public suspend fun AuthRequirement<*>.accepts(auth: Authentication<*>?): Boolean =
    when (check(auth)) {
        is AuthRequirement.Result.Rejected -> false
        is AuthRequirement.Result.Accepted -> true
    }

/**
 * Asserts that the given [Authentication] will be accepted by this [AuthRequirement], answering a refusal the way
 * RFC 6750 asks of a bearer-token resource:
 * - no authentication: [UnauthorizedException] (401) with `WWW-Authenticate: Bearer`;
 * - authenticated, but without the scopes needed: [ForbiddenException] (403) with
 *   `WWW-Authenticate: Bearer error="insufficient_scope", scope="…"`;
 * - authenticated, but refused for anything else: [ForbiddenException] (403).
 *
 * Prefer the overload taking the request, which can also tell an unreadable token from none.
 * */
context(server: ServerRuntime)
public suspend fun <SUBJECT : HasId<*>?> AuthRequirement<SUBJECT>.assert(
    auth: Authentication<*>?,
): Authentication<SUBJECT & Any>? =
    when (val r = check(auth)) {
        is AuthRequirement.Result.Rejected -> throw rejection(r, auth, request = null)
        is AuthRequirement.Result.Accepted<SUBJECT> -> r.auth
    }

/**
 * Asserts that [request]'s [Authentication] will be accepted by this [AuthRequirement], as the other [assert] does.
 * A request that sent an `Authorization` header no reader could read gets a 401 `error="invalid_token"`, and at a
 * registered [ProtectedResource] the challenge also carries its `resource_metadata` and `scope`.
 * */
context(server: ServerRuntime)
public suspend fun <SUBJECT : HasId<*>?> AuthRequirement<SUBJECT>.assert(
    request: Request<*>,
): Authentication<SUBJECT & Any>? {
    val auth = request.authentication()
    return when (val r = check(auth)) {
        is AuthRequirement.Result.Rejected -> throw rejection(r, auth, request)
        is AuthRequirement.Result.Accepted<SUBJECT> -> r.auth
    }
}

context(server: ServerRuntime)
private fun AuthRequirement<*>.rejection(
    rejected: AuthRequirement.Result.Rejected,
    auth: Authentication<*>?,
    request: Request<*>?,
): HttpStatusException {
    val message = """
        You do not meet the authorization criteria
        Requirement: ${this.naturalLanguage()}
        Failed On: ${rejected.reason}
    """.trimIndent()
    return when {
        auth == null -> {
            // Readers pass over tokens they can't make sense of, so a token was sent but none was read.
            val unreadable = request?.headers?.get(HttpHeader.Authorization) != null
            val error = if (unreadable) "invalid_token" else null
            UnauthorizedException(message = message, headers = bearerChallenge(request, error, scopes = null))
        }
        rejected.insufficientScopes != null -> ForbiddenException(
            message = message,
            headers = bearerChallenge(request, "insufficient_scope", rejected.insufficientScopes),
        )
        else -> ForbiddenException(message = message)
    }
}


public fun <SUBJECT : HasId<*>?> AuthRequirement<SUBJECT>.subscope(subscope: Subscope): AuthRequirement<SUBJECT> =
    subscope(listOf(subscope))

public fun <SUBJECT : HasId<*>?> AuthRequirement<SUBJECT>.subscope(subscope: String): AuthRequirement<SUBJECT> =
    subscope(listOf(Subscope(subscope)))


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
    requirement: (suspend context(ServerRuntime) (Authentication<SUBJECT>) -> Boolean)? = null,
): AuthRequirement<SUBJECT> =
    AuthRequirement.AuthenticatedAs(this, scopes, maxAge, requirement)

/**
 * Creates an [AuthRequirement] that requires authentication of this [SUBJECT] type,
 * along with the other specified requirements.
 * */
public fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> PrincipalType<SUBJECT, ID>.require(
    scope: RequiredScope = RequiredScope.root,
    maxAge: Duration? = null,
    requirement: (suspend context(ServerRuntime) (Authentication<SUBJECT>) -> Boolean)? = null,
): AuthRequirement<SUBJECT> =
    AuthRequirement.AuthenticatedAs(this, setOf(scope), maxAge, requirement)


public fun <T : HasId<*>?> AuthRequirement<T>.options(): Set<AuthRequirement<T>> =
    if (this is Options) options else setOf(this)

public infix fun <SUBJECT : HasId<*>?> AuthRequirement<SUBJECT>.or(
    other: AuthRequirement<SUBJECT>,
): AuthRequirement<SUBJECT> =
    Options(options() + other.options())

context(builder: ServerBuilder)
public var AuthRequirement.Companion.isSuperUser: AuthRequirement<HasId<*>>
    get() = AuthRequirement.IsSuperUser
    set(value) {
        AuthRequirement.IsSuperUser set value
    }

context(builder: ServerBuilder)
public var AuthRequirement.Companion.isAdmin: AuthRequirement<HasId<*>>
    get() = AuthRequirement.IsAdmin
    set(value) {
        AuthRequirement.IsAdmin set value
    }

context(builder: ServerBuilder)
public var AuthRequirement.Companion.isDeveloper: AuthRequirement<HasId<*>>
    get() = AuthRequirement.IsDeveloper
    set(value) {
        AuthRequirement.IsDeveloper set value
    }


context(_: Engine)
public fun AuthRequirement<*>.naturalLanguage(markdown: Boolean = false): String = when (this) {
    is AuthRequirement.Options -> options.joinToString(if (markdown) " *or* " else " or ") { it.naturalLanguage(markdown) }
    is AuthRequirement.AuthSetting -> setting()?.let { "$this (${it.naturalLanguage(markdown)})" } ?: this.toString()
    else -> this.toString()
}