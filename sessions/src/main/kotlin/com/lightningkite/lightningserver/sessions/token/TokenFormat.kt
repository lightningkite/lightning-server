package com.lightningkite.lightningserver.sessions.token

import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.HasId
import kotlin.time.Duration

public interface TokenFormat {
    public val type: String get() = "Bearer"

    /** How long a token from [create] stays valid. Token invalidations are kept for this long. */
    public val expiration: Duration

    context(server: ServerRuntime)
    public suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> create(
        principal: PrincipalType<SUBJECT, ID>,
        auth: Authentication<SUBJECT>,
    ): String

    context(server: ServerRuntime)
    public suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> read(
        principal: PrincipalType<SUBJECT, ID>,
        value: String,
    ): Authentication<SUBJECT>?
}

