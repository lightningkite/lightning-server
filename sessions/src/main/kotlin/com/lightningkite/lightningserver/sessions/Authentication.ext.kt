package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.GrantedScope
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.data.SerializableCache
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
    expiration: Instant? = null,
    scopes: Set<GrantedScope> = setOf(GrantedScope.root),
    cache: SerializableCache? = null,
): Authentication<SUBJECT> =
    Authentication(
        principalType = principalType,
        id = id,
        sessionId = sessionId?.toString(),
        issuedAt = issuedAt,
        expiration = expiration,
        scopes = scopes,
        cache = cache
    )
