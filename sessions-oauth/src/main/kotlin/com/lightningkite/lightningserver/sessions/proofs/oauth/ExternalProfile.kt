package com.lightningkite.lightningserver.sessions.proofs.oauth

import com.lightningkite.services.data.GenerateDataClassPaths
import kotlinx.serialization.Serializable

@GenerateDataClassPaths
@Serializable
public data class ExternalProfile(
    val id: String? = null,
    val email: String? = null,
    val username: String? = null,
    val name: String? = null,
    val image: String? = null,
    val providerName: String?,
)