package com.lightningkite.lightningserver.auth

import kotlinx.serialization.Serializable

@Serializable
data class ExternalServiceLogin(
    val service: String,
    val username: String? = null,
    val email: String? = null,
    val avatar: String? = null
)

