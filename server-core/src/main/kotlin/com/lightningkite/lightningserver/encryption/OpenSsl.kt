package com.lightningkite.lightningserver.encryption

import java.security.MessageDigest
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object OpenSsl {
    fun decrypt(secretKeyClear: ByteArray, bytes: ByteArray): ByteArray {
        var cipherBytes: ByteArray = bytes
        val salt = cipherBytes.copyOfRange(8, 16)
        cipherBytes = cipherBytes.copyOfRange(16, cipherBytes.size)
        val passAndSalt: ByteArray = secretKeyClear + salt
        val md = MessageDigest.getInstance("SHA-256")
        val key = md.digest(passAndSalt)
        val secretKey = SecretKeySpec(key, "AES")
        md.reset()
        val iv = Arrays.copyOfRange(
            md.digest(key + passAndSalt), 0, 16
        ) // Decrypt

        val cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")
        cipher.init(
            Cipher.DECRYPT_MODE, secretKey,
            IvParameterSpec(iv)
        )
        return cipher.doFinal(cipherBytes)
    }
}