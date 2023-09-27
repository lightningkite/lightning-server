package com.lightningkite.lightningserver.encryption

import com.lightningkite.lightningserver.bytes.toHexString
import org.bouncycastle.cms.RecipientId.password
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.security.spec.KeySpec
import java.util.*
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec


interface Encryptor {
    val name: String
    fun encrypt(bytes: ByteArray): ByteArray
    fun decrypt(bytes: ByteArray): ByteArray
    fun encryptSize(size: Int): Int
    fun decryptSize(size: Int): Int
//    fun encrypt(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use {
//        encrypt(it).write(bytes)
//        it
//    }.toByteArray()
//    fun decrypt(bytes: ByteArray): ByteArray = decrypt(ByteArrayInputStream(bytes)).readBytes()
//    fun encrypt(stream: OutputStream): OutputStream
//    fun decrypt(stream: InputStream): InputStream

    class AesCbcPkcs5Padding(val key: SecretKeySpec): Encryptor {
        companion object {
            const val GCM_IV_LENGTH = 12
            const val GCM_TAG_LENGTH = 16
        }

        constructor(bytes: ByteArray): this(SecretKeySpec(bytes, "AES"))
        constructor(key: String): this(Base64.getUrlDecoder().decode(key))
        val keyString: String get() = Base64.getUrlEncoder().encodeToString(key.encoded)

        override val name: String
            get() = "AES/GCM/NoPadding"

//        override fun encrypt(stream: OutputStream): OutputStream {
//            val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
//            val nonce = ByteArray(GCM_IV_LENGTH)
//            SecureRandom().nextBytes(nonce)
//            println("nonce: ${nonce.toHexString()}")
//            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH * 8, nonce))
//            stream.write(nonce)
//            return CipherOutputStream(stream, cipher)
//        }
//
//        override fun decrypt(stream: InputStream): InputStream {
//            val original = stream
//            val nonce = ByteArray(GCM_IV_LENGTH)
//            original.read(nonce)
//            println("nonce: ${nonce.toHexString()}")
//            val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
//            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH * 8, nonce))
//            return CipherInputStream(original, cipher)
//        }

        override fun encrypt(bytes: ByteArray): ByteArray {
            val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val nonce = ByteArray(GCM_IV_LENGTH)
            SecureRandom().nextBytes(nonce)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH * 8, nonce))
            return nonce + cipher.doFinal(bytes)
        }

        override fun decrypt(bytes: ByteArray): ByteArray {
            val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH * 8, bytes, 0, GCM_IV_LENGTH))
            return cipher.doFinal(bytes, GCM_IV_LENGTH, bytes.size - GCM_IV_LENGTH)
        }

        override fun encryptSize(size: Int): Int = size + GCM_IV_LENGTH + GCM_TAG_LENGTH
        override fun decryptSize(size: Int): Int = size - GCM_IV_LENGTH - GCM_TAG_LENGTH
    }
}