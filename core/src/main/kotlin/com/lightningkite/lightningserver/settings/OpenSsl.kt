package com.lightningkite.lightningserver.settings

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object OpenSsl {
    internal fun ByteArray.decryptAesCbcPkcs5(key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv)
        )
        return cipher.doFinal(this)
    }


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
        val iv = md.digest(key + passAndSalt).copyOfRange(0, 16) // Decrypt
        return cipherBytes.decryptAesCbcPkcs5(key, iv)
    }

}