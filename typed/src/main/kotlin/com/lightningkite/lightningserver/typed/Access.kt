package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.assert
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.data.get
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.pathing.HasContextualPath
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.lightningserver.ForbiddenException
import com.lightningkite.lightningserver.pathing.ResolvedPath
import com.lightningkite.services.database.HasId

/**
 * Represents authentication access, which may or may not be from a [Request]. This is a convenience wrapper to
 * provide both the authentication and request the authentication came from.
 *
 * To create an [Access] instance use the [Request.access] method, or if you only care about the auth
 * use the [AuthAccess] factory function.
 * */
@ConsistentCopyVisibility
public data class Access<out REQ : Request<PATH>?, out PATH : PathSpec, SUBJECT : HasId<*>?> internal constructor(
    public val request: REQ,
    public val authOrNull: Authentication<SUBJECT & Any>?
)

public typealias HttpAccess<PATH, SUBJECT> = Access<HttpRequest<PATH>, PATH, SUBJECT>
public typealias WebSocketConnectRequestAccess<PATH, SUBJECT> = Access<WebSocketConnectRequest<PATH>, PATH, SUBJECT>
public typealias AuthAccess<SUBJECT> = Access<*, *, SUBJECT>

/**
 * Retrieves the [ResolvedPath] of this [Request]
 * */
context(runtime: ServerRuntime)
public val <REQ : Request<PATH>, PATH : PathSpec> Access<REQ, PATH, *>.path: ResolvedPath<PATH> get() = request.pathInContext

public val <SUBJECT : HasId<*>> Access<*, *, SUBJECT>.auth: Authentication<SUBJECT> get() = authOrNull!! // safe because the type is non-null (by convention)


/**
 * Creates a new [Access] for this request by asserting the provided [AuthRequirement].
 *
 * This method calls [AuthRequirement.assert] to retrieve the authentication.
 *
 * @throws [ForbiddenException] if the authentication in this request does not satisfy [requirement]
 *
 * @see [AuthRequirement.assert]
 * */
context(server: ServerRuntime)
public suspend fun <REQUEST : Request<PATH>, PATH : PathSpec, SUBJECT : HasId<*>?> REQUEST.access(
    requirement: AuthRequirement<SUBJECT>
): Access<REQUEST, PATH, SUBJECT> =
    Access(this, requirement.assert(get(Authentication.CacheKey)))

@JvmName("AuthAccessNullable")
public fun <SUBJECT : HasId<*>?> AuthAccess(auth: Authentication<SUBJECT & Any>?): AuthAccess<SUBJECT> = Access<Nothing?, Nothing, SUBJECT>(null, auth)
public fun <SUBJECT : HasId<*>> AuthAccess(auth: Authentication<SUBJECT>): AuthAccess<SUBJECT> = Access<Nothing?, Nothing, SUBJECT>(null, auth)


@JvmName("authNullable")
context(server: ServerRuntime)
public suspend fun <SUBJECT: HasId<*>> Request<*>.auth(auth: AuthRequirement<SUBJECT?>): Authentication<SUBJECT>? {
    return auth.assert(this[Authentication.CacheKey])
}

@JvmName("auth")
context(server: ServerRuntime)
public suspend fun <SUBJECT: HasId<*>> Request<*>.auth(auth: AuthRequirement<SUBJECT>): Authentication<SUBJECT> {
    return auth.assert(this[Authentication.CacheKey])!!
}

context(server: ServerRuntime, access: Access<*, *, *>)
public suspend operator fun AuthRequirement.AuthSetting.invoke(): Boolean = accepts(access.authOrNull)