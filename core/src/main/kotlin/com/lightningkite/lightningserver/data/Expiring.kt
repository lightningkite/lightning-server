package com.lightningkite.lightningserver.data

import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Instant

@Serializable
public data class Expiring<T>(
    val value: T,
    val expiresAt: Instant?
) {
    context(server: ServerRuntime)
    public val expired: Boolean get() = expiresAt != null && expiresAt <= server.clock.now()
}

context(server: ServerRuntime)
public fun <T> Expiring(value: T, expireAfter: Duration?): Expiring<T> = Expiring(value, expireAfter?.let { server.clock.now() + it })