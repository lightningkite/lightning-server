package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.RequestPredicates
import com.lightningkite.lightningserver.data.SerializableCache
import com.lightningkite.lightningserver.data.get
import com.lightningkite.lightningserver.data.set
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.HasId
import kotlin.time.Instant
import kotlin.uuid.Uuid

context(server: ServerRuntime)
public fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> Authentication(
    principalType: PrincipalType<SUBJECT, ID>,
    id: ID,
    sessionId: Uuid?,
    issuedAt: Instant = server.clock.now(),
    limitTo: RequestPredicates? = null,
    forbid: RequestPredicates? = null,
    cache: SerializableCache? = null
): Authentication<SUBJECT, ID> =
    Authentication(
        server,
        principalType,
        id,
        issuedAt,
        limitTo,
        forbid,
        cache = cache
    ).also {
        if (sessionId != null) it[Session] = sessionId
    }

context(_: ServerRuntime)
public val Authentication<*, *>.sessionId: Uuid? get() = get(Session)