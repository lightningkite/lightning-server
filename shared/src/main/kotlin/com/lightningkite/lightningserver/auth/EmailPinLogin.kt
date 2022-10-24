package com.lightningkite.lightningserver.auth

import kotlinx.serialization.Serializable

@Serializable
data class EmailPinLogin(
    val email: String,
    val pin: String
) {
}

@Serializable
data class PhonePinLogin(
    val phone: String,
    val pin: String
) {
}