package com.lightningkite.lightningserver.email

import kotlinx.serialization.Serializable

@Serializable
data class SmtpConfig(
    val hostName: String,
    val port: Int,
    val username: String?,
    val password: String?,
    val fromEmail: String,
    val fromEmailLabel: String? = null,
)