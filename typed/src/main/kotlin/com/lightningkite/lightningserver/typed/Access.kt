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
import com.lightningkite.services.database.HasId

public class Access<REQ : Request<PATH>, PATH : PathSpec, SUBJECT : HasId<*>?> internal constructor(
    public val request: REQ,
    public val authOrNull: Authentication<SUBJECT & Any>?,
) : HasContextualPath<PATH> by request

context(server: ServerRuntime)
internal suspend fun <REQUEST : Request<PATH>, PATH : PathSpec, SUBJECT : HasId<*>?> REQUEST.access(
    auth: AuthRequirement<SUBJECT>
): Access<REQUEST, PATH, SUBJECT> = Access(this, auth.assert(get(Authentication.CacheKey)))

public typealias HttpAccess<PATH, SUBJECT> = Access<HttpRequest<PATH>, PATH, SUBJECT>

public typealias AuthAccess<SUBJECT> = Access<*, *, SUBJECT>

public val <SUBJECT : HasId<*>> Access<*, *, SUBJECT>.auth: Authentication<SUBJECT>
    get() = authOrNull!! // safe because the type is non-null

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