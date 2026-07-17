package com.lightningkite.lightningserver.sessions.proofs.oauth

import kotlinx.serialization.Serializable

@Serializable
public data class ExternalProfile(
    val id: String? = null,
    val email: String? = null,
    val username: String? = null,
    val name: String? = null,
    val image: String? = null,
)