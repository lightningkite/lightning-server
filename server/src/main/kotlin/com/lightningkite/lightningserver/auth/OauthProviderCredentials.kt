package com.lightningkite.lightningserver.auth

import kotlinx.serialization.Serializable


@Serializable
data class OauthProviderCredentials(
    val id: String,
    val secret: String
)