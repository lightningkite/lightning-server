package com.lightningkite.lightningserver.auth

import io.ktor.util.*
import java.security.MessageDigest
import java.security.SecureRandom

fun String.secureHash(): String {
    val salt = ByteArray(16)
    SecureRandom().nextBytes(salt)
    return salt.encodeBase64() + "." + MessageDigest.getInstance("SHA-256")
        .digest(salt + this.toByteArray())
        .encodeBase64()
}
fun String.checkHash(againstHash: String): Boolean {
    val salt = againstHash.substringBefore('.').decodeBase64Bytes()
    val rest = againstHash.substringAfter('.')
    return MessageDigest.getInstance("SHA-256")
        .digest(salt + this.toByteArray())
        .encodeBase64() == rest
}