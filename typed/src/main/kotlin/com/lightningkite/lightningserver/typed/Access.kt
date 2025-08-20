package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.data.get
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.pathing.HasContextualPath
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.HasId

public data class Access<REQ : Request<PATH>, PATH : PathSpec, SUBJECT : HasId<ID>?, ID : Comparable<ID>>(
    val request: REQ,
    val authOrNull: Authentication<SUBJECT & Any, ID>?,
) : HasContextualPath<PATH> by request

context(server: ServerRuntime)
public suspend fun <REQUEST : Request<PATH>, PATH : PathSpec, SUBJECT : HasId<ID>?, ID : Comparable<ID>> REQUEST.access(
    auth: AuthOptions<SUBJECT, ID>
): Access<REQUEST, PATH, SUBJECT, ID> = Access(this, auth.assert(get(Authentication.CacheKey)))

public typealias HttpAccess<PATH, SUBJECT, ID> = Access<HttpRequest<PATH>, PATH, SUBJECT, ID>

public val <SUBJECT : HasId<ID>, ID : Comparable<ID>> Access<*, *, SUBJECT, ID>.auth: Authentication<SUBJECT, ID>
    get() = authOrNull!! // safe because the type is non-null
