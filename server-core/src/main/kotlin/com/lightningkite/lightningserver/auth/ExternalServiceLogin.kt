package com.lightningkite.lightningserver.auth

import kotlinx.serialization.Serializable

/**
 * Information about someone's account in a different service.
 * Used for OAuth.
 */
@Serializable
data class ExternalServiceLogin(
    val service: String,
    val username: String? = null,
    val email: String? = null,
    val avatar: String? = null
)

