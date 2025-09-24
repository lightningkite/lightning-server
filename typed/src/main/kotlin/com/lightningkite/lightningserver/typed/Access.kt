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
import com.lightningkite.services.database.HasId

/**
 * Represents authentication access, which may or may not be from a [Request]. This is a convenience wrapper to
 * provide both the authentication and request the authentication came from.
 *
 * To create an [Access] instance use the [Request.access] method.
 * */
public sealed interface Access<out REQ : Request<PATH>?, out PATH : PathSpec, SUBJECT : HasId<*>?> {
    public val request: REQ
    public val authOrNull: Authentication<SUBJECT & Any>?

    public class FromRequest<REQ : Request<PATH>, PATH : PathSpec, SUBJECT : HasId<*>?> internal constructor(
        override val request: REQ,
        override val authOrNull: Authentication<SUBJECT & Any>?
    ) : Access<REQ, PATH, SUBJECT>, HasContextualPath<PATH> by request
}

public typealias HttpAccess<PATH, SUBJECT> = Access.FromRequest<HttpRequest<PATH>, PATH, SUBJECT>
public typealias WebSocketConnectRequestAccess<PATH, SUBJECT> = Access.FromRequest<WebSocketConnectRequest<PATH>, PATH, SUBJECT>

public val <SUBJECT : HasId<*>> Access<*, *, SUBJECT>.auth: Authentication<SUBJECT> get() = authOrNull!! // safe because the type is non-null


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
): Access.FromRequest<REQUEST, PATH, SUBJECT> =
    Access.FromRequest(this, requirement.assert(get(Authentication.CacheKey)))


public typealias AuthAccess<SUBJECT> = Access<*, *, SUBJECT>

private class AccessWithoutRequest<SUBJECT : HasId<*>?>(
    override val authOrNull: Authentication<SUBJECT & Any>?
) : Access<Nothing?, PathSpec, SUBJECT> {
    override val request: Nothing? get() = null
}

public fun <SUBJECT : HasId<*>> AuthAccess(auth: Authentication<SUBJECT>?): AuthAccess<SUBJECT?> = AccessWithoutRequest(auth)
public fun <SUBJECT : HasId<*>> AuthAccess(auth: Authentication<SUBJECT>): AuthAccess<SUBJECT> = AccessWithoutRequest(auth)


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