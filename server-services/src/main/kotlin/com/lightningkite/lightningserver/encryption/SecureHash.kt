package com.lightningkite.lightningserver.encryption

import com.lightningkite.fromBase64
import com.lightningkite.toBase64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec


private const val prefix = "PBKDF2WithHmacSHA512."

/**
 * Securely hashes a string in a way that could be used for password storage.
 */
fun String.secureHash(): String {
    if (this.startsWith(prefix)) return this
    if (this.isEmpty()) return ""
    val salt = ByteArray(16)
    SecureRandom().nextBytes(salt)
    val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
    val spec = PBEKeySpec(this.toCharArray(), salt, 100000, 512)
    val key = skf.generateSecret(spec)
    return prefix + salt.toBase64() + "." + key.encoded.toBase64()
}

/**
 * Checks hashes generated by [secureHash].
 */
fun String.checkAgainstHash(againstHash: String): Boolean {
    if (againstHash.isEmpty()) return false
    val against = againstHash.removePrefix(prefix)
    val salt = against.substringBefore('.').fromBase64()
    val rest = against.substringAfter('.')
    val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
    val spec = PBEKeySpec(this.toCharArray(), salt, 100000, 512)
    val key = skf.generateSecret(spec)
    return key.encoded.toBase64() == rest
}