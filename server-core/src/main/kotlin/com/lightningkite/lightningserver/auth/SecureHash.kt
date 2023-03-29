package com.lightningkite.lightningserver.auth

import io.ktor.util.*
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec


private const val prefix = "PBKDF2WithHmacSHA512."
fun String.secureHash(): String {
    if (this.startsWith(prefix)) return this
    if (this.isEmpty()) return ""
    val salt = ByteArray(16)
    SecureRandom().nextBytes(salt)
    val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
    val spec = PBEKeySpec(this.toCharArray(), salt, 100000, 512)
    val key = skf.generateSecret(spec)
    return prefix + salt.encodeBase64() + "." + key.encoded.encodeBase64()
}

fun String.checkHash(againstHash: String): Boolean {
    if (againstHash.isEmpty()) return false
    val against = againstHash.removePrefix(prefix)
    val salt = against.substringBefore('.').decodeBase64Bytes()
    val rest = against.substringAfter('.')
    val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
    val spec = PBEKeySpec(this.toCharArray(), salt, 100000, 512)
    val key = skf.generateSecret(spec)
    return key.encoded.encodeBase64() == rest
}
