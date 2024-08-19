@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningserver.auth.subject

import com.lightningkite.UUID
import com.lightningkite.lightningdb.References
import com.lightningkite.lightningserver.auth.oauth.OauthClient
import com.lightningkite.now
import kotlinx.datetime.Instant
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlin.time.Duration.Companion.minutes

@Serializable
internal data class FutureSession<ID>(
    val subjectId: ID,
    val scopes: Set<String>,
    @Contextual val expires: Instant = now().plus(5.minutes),
    val originalSessionId: UUID?,
    val label: String? = null,
    @Contextual val sessionExpiration: Instant? = null,
    @References(OauthClient::class) val oauthClient: String? = null
)