package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.pathing.HasContextualPath
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.services.database.HasId


public data class RequestAndAuth<REQ : Request<PATH>, PATH : PathSpec, SUBJECT : HasId<ID>?, ID : Comparable<ID>>(
    val request: REQ,
    val authOrNull: Authentication<SUBJECT & Any, ID>?,
) : HasContextualPath<PATH> by request

public typealias HttpRequestAndAuth<PATH, SUBJECT, ID> = RequestAndAuth<HttpRequest<PATH>, PATH, SUBJECT, ID>

public val <SUBJECT : HasId<ID>, ID : Comparable<ID>> RequestAndAuth<*, *, SUBJECT, ID>.auth: Authentication<SUBJECT, ID>
    get() = authOrNull!! // safe because the type is non-null
