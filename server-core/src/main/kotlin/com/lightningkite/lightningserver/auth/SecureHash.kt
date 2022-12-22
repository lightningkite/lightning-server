package com.lightningkite.lightningserver.auth

import io.ktor.util.*
import java.security.MessageDigest
import java.security.SecureRandom

private const val prefix = "SHA-256|"
fun String.secureHash(): String {
    if(this.startsWith(prefix)) return this
    if(this.isEmpty()) return ""
    val salt = ByteArray(16)
    SecureRandom().nextBytes(salt)
    return prefix + salt.encodeBase64() + "." + MessageDigest.getInstance("SHA-256")
        .digest(salt + this.toByteArray())
        .encodeBase64()
}
fun String.checkHash(againstHash: String): Boolean {
    if(againstHash.isEmpty()) return false
    val against = againstHash.removePrefix(prefix)
    val salt = against.substringBefore('.').decodeBase64Bytes()
    val rest = against.substringAfter('.')
    return MessageDigest.getInstance("SHA-256")
        .digest(salt + this.toByteArray())
        .encodeBase64() == rest
}