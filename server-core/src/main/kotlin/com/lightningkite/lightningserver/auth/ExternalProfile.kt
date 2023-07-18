package com.lightningkite.lightningserver.auth

import kotlinx.serialization.Serializable

@Serializable
data class ExternalProfile(
    val email: String? = null,
    val username: String? = null,
    val name: String? = null,
    val image: String? = null,
)