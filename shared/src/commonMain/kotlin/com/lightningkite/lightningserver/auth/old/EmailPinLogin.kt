
package com.lightningkite.lightningserver.auth.old


import kotlinx.serialization.Serializable

@Serializable
data class EmailPinLogin(
    val email: String,
    val pin: String,
) {
}

@Serializable
data class PhonePinLogin(
    val phone: String,
    val pin: String,
) {
}

@Serializable
data class PasswordLogin(
    val username: String,
    val password: String,
) {
}
