package com.lightningkite.lightningserver.encryption

import java.security.MessageDigest
import java.util.*
import com.lightningkite.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

fun ByteArray.decryptAesCbcPkcs5(key: ByteArray, iv: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")
    cipher.init(
        Cipher.DECRYPT_MODE,
        SecretKeySpec(key, "AES"),
        IvParameterSpec(iv)
    )
    return cipher.doFinal(this)
}

fun ByteArray.encryptAesCbcPkcs5(key: ByteArray, iv: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")
    cipher.init(
        Cipher.ENCRYPT_MODE,
        SecretKeySpec(key, "AES"),
        IvParameterSpec(iv)
    )
    return cipher.doFinal(this)
}

object OpenSsl {
    @Deprecated(
        "Deprecated due to bad naming, use new location",
        ReplaceWith("OpenSsl.decryptAesCbcPkcs5Sha256(bytes, secretKeyClear)")
    )
    fun decrypt(secretKeyClear: ByteArray, bytes: ByteArray): ByteArray =
        decryptAesCbcPkcs5Sha256(bytes, secretKeyClear)

    fun decryptAesCbcPkcs5Sha256(bytes: ByteArray, password: ByteArray): ByteArray {
        var cipherBytes: ByteArray = bytes
        val salt = cipherBytes.copyOfRange(8, 16)
        cipherBytes = cipherBytes.copyOfRange(16, cipherBytes.size)
        val passAndSalt: ByteArray = password + salt
        val md = MessageDigest.getInstance("SHA-256")
        val key = md.digest(passAndSalt)
        md.reset()
        val iv = Arrays.copyOfRange(
            md.digest(key + passAndSalt), 0, 16
        ) // Decrypt
        return cipherBytes.decryptAesCbcPkcs5(key, iv)
    }

    fun decryptAesCbcPkcs5md5(bytes: ByteArray, password: ByteArray): ByteArray {
        var cipherBytes: ByteArray = bytes
        val salt = cipherBytes.copyOfRange(8, 16)
        cipherBytes = cipherBytes.copyOfRange(16, cipherBytes.size)
        val passAndSalt: ByteArray = password + salt
        val md = MessageDigest.getInstance("MD5")
        val key = md.digest(passAndSalt)
        md.reset()
        val iv = Arrays.copyOfRange(
            md.digest(key + passAndSalt), 0, 16
        ) // Decrypt
        return cipherBytes.decryptAesCbcPkcs5(key, iv)
    }
}