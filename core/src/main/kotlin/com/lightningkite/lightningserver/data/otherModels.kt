package com.lightningkite.lightningserver.data

import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
public data class Expiring<T>(
    val value: T,
    @Contextual val expires: Instant?
) {
    context(server: ServerRuntime)
    public val expired: Boolean get() = expires != null && expires <= server.clock.now()
}