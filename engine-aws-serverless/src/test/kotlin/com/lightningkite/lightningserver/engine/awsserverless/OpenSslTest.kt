package com.lightningkite.lightningserver.engine.awsserverless

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

class OpenSslTest {

    private fun deriveKeyAndIv(password: ByteArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val md = MessageDigest.getInstance("SHA-256")
        val passAndSalt = password + salt
        val key = md.digest(passAndSalt)
        md.reset()
        val iv = md.digest(key + passAndSalt).copyOfRange(0, 16)
        return key to iv
    }

    private fun encryptAesCbcPkcs5(plain: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(plain)
    }

    @Test
    fun decrypt_roundTrip_matchesPlaintext() {
        val password = "super-secret".toByteArray()
        val salt = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val (key, iv) = deriveKeyAndIv(password, salt)
        val plain = "Hello AWS Serverless!".encodeToByteArray()
        val cipherBytes = encryptAesCbcPkcs5(plain, key, iv)
        val saltedPrefix = "Salted__".encodeToByteArray()
        val payload = saltedPrefix + salt + cipherBytes
        val decrypted = OpenSsl.decryptAesCbcPkcs5Sha256(payload, password)
        assertContentEquals(plain, decrypted)
    }

    @Test
    fun decrypt_wrongPassword_doesNotMatchAndMayThrow() {
        val correctPassword = "correct".toByteArray()
        val wrongPassword = "wrong".toByteArray()
        val salt = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2)
        val (key, iv) = deriveKeyAndIv(correctPassword, salt)
        val plain = ByteArray(32) { it.toByte() }
        val cipherBytes = encryptAesCbcPkcs5(plain, key, iv)
        val payload = "Salted__".encodeToByteArray() + salt + cipherBytes
        try {
            val decrypted = OpenSsl.decryptAesCbcPkcs5Sha256(payload, wrongPassword)
            // If no exception, ensure it doesn't equal the original plaintext
            assertNotEquals(plain.toList(), decrypted.toList())
        } catch (e: Exception) {
            // Some providers will throw due to bad padding on decryption
            // Either behavior is acceptable for this negative test
        }
    }

    @Test
    fun decrypt_emptyPlaintext_roundTrip() {
        val password = "p".toByteArray()
        val salt = ByteArray(8) { 0 }
        val (key, iv) = deriveKeyAndIv(password, salt)
        val plain = byteArrayOf()
        val cipherBytes = encryptAesCbcPkcs5(plain, key, iv)
        val payload = "Salted__".encodeToByteArray() + salt + cipherBytes
        val decrypted = OpenSsl.decryptAesCbcPkcs5Sha256(payload, password)
        assertContentEquals(plain, decrypted)
    }
}
