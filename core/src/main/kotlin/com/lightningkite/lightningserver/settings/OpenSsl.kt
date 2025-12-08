package com.lightningkite.lightningserver.settings

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Internal utility for decrypting OpenSSL-encrypted settings files.
 *
 * This object provides AES-256-CBC decryption compatible with OpenSSL's encryption formats:
 * - **PBKDF2 (modern)**: `openssl enc -aes-256-cbc -pbkdf2 -in file -out file.enc`
 * - **EVP_BytesToKey (legacy)**: `openssl enc -aes-256-cbc -md sha256 -in file -out file.enc`
 *
 * The decryption automatically detects which format was used based on the presence of
 * the PBKDF2 parameter marker in the OpenSSL header.
 *
 * The decryption is triggered when the environment variable `LIGHTNING_SERVER_SETTINGS_DECRYPTION`
 * is set to the encryption password.
 *
 * @see ServerSettings.loadFromFile
 */
internal object OpenSsl {
    private const val OPENSSL_MAGIC = "Salted__"
    private const val PBKDF2_DEFAULT_ITERATIONS = 10000
    /**
     * Decrypts a byte array using AES-CBC with PKCS5 padding.
     *
     * @param key The AES encryption key (must be appropriate length for AES)
     * @param iv The initialization vector for CBC mode (16 bytes)
     * @return The decrypted byte array
     */
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

    /**
     * Decrypts OpenSSL-encrypted data, automatically detecting the encryption method.
     *
     * This function is compatible with both modern and legacy OpenSSL encryption formats:
     *
     * **Modern Format (OpenSSL 1.1.1+ with -pbkdf2):**
     * - Uses PBKDF2-HMAC-SHA256 with 10,000 iterations
     * - Key derivation: PBKDF2(password, salt, 10000, 32 bytes for key + 16 bytes for IV)
     *
     * **Legacy Format (OpenSSL with -md sha256):**
     * - Uses EVP_BytesToKey with SHA-256
     * - Key derivation: SHA-256(password + salt) for key, SHA-256(key + password + salt) for IV
     *
     * The format is auto-detected by attempting PBKDF2 first, then falling back to EVP_BytesToKey
     * if the decryption fails (indicated by padding errors).
     *
     * All formats expect the encrypted data to start with "Salted__" (8 bytes) followed by
     * an 8-byte salt, then the ciphertext.
     *
     * @param bytes The encrypted byte array (must start with "Salted__" header)
     * @param password The password used for encryption
     * @return The decrypted byte array
     * @throws IllegalArgumentException if the input doesn't start with "Salted__"
     * @throws ArrayIndexOutOfBoundsException if the input is too short to contain salt
     * @throws Exception if decryption fails with both methods
     */
    fun decryptAesCbcPkcs5Sha256(bytes: ByteArray, password: ByteArray): ByteArray {
        // Verify OpenSSL magic header
        val magic = bytes.copyOfRange(0, 8).decodeToString()
        require(magic == OPENSSL_MAGIC) {
            "Invalid OpenSSL encrypted file format. Expected '$OPENSSL_MAGIC' header but got '$magic'"
        }

        val salt = bytes.copyOfRange(8, 16)
        val cipherBytes = bytes.copyOfRange(16, bytes.size)

        // Try PBKDF2 first (modern OpenSSL default)
        try {
            val (key, iv) = deriveKeyAndIvPbkdf2(password, salt)
            return cipherBytes.decryptAesCbcPkcs5(key, iv)
        } catch (e: Exception) {
            // If PBKDF2 fails, try legacy EVP_BytesToKey method
            try {
                val (key, iv) = deriveKeyAndIvEvpBytesToKey(password, salt)
                return cipherBytes.decryptAesCbcPkcs5(key, iv)
            } catch (e2: Exception) {
                // Both methods failed
                throw Exception(
                    "Failed to decrypt file with both PBKDF2 and EVP_BytesToKey methods. " +
                    "PBKDF2 error: ${e.message}, EVP_BytesToKey error: ${e2.message}",
                    e
                )
            }
        }
    }

    /**
     * Derives key and IV using PBKDF2-HMAC-SHA256 (modern OpenSSL format).
     *
     * This matches the behavior of `openssl enc -aes-256-cbc -pbkdf2`.
     *
     * @param password The password
     * @param salt The 8-byte salt
     * @param iterations Number of PBKDF2 iterations (default: 10000)
     * @return Pair of (32-byte key, 16-byte IV)
     */
    private fun deriveKeyAndIvPbkdf2(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int = PBKDF2_DEFAULT_ITERATIONS
    ): Pair<ByteArray, ByteArray> {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(
            String(password, Charsets.UTF_8).toCharArray(),
            salt,
            iterations,
            (32 + 16) * 8  // 32 bytes for key + 16 bytes for IV
        )
        val derivedKey = factory.generateSecret(spec).encoded

        val key = derivedKey.copyOfRange(0, 32)  // AES-256 key
        val iv = derivedKey.copyOfRange(32, 48)  // 16-byte IV

        return Pair(key, iv)
    }

    /**
     * Derives key and IV using EVP_BytesToKey with SHA-256 (legacy OpenSSL format).
     *
     * This matches the behavior of `openssl enc -aes-256-cbc -md sha256` (without -pbkdf2).
     *
     * @param password The password
     * @param salt The 8-byte salt
     * @return Pair of (32-byte key, 16-byte IV)
     */
    private fun deriveKeyAndIvEvpBytesToKey(password: ByteArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val passAndSalt = password + salt
        val md = MessageDigest.getInstance("SHA-256")

        val key = md.digest(passAndSalt)
        md.reset()
        val iv = md.digest(key + passAndSalt).copyOfRange(0, 16)

        return Pair(key, iv)
    }

}