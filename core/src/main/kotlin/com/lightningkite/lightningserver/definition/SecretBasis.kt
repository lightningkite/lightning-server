package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlinx.serialization.*
import javax.crypto.Mac
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec

/**
 * A secure basis for cryptographic operations.
 * This class provides a foundation for cryptographic operations like hashing and encryption.
 * 
 * Note: This implementation currently uses JVM-specific cryptography libraries.
 * For multiplatform support, this should be refactored to use the dev.whyoleg.cryptography
 * libraries that are already included in the project dependencies.
 * 
 * TODO: Implement multiplatform support using dev.whyoleg.cryptography:
 * 1. Create expect/actual declarations for platform-specific implementations
 * 2. Implement JVM version using either JVM crypto or dev.whyoleg.cryptography
 * 3. Implement JS/Native versions using dev.whyoleg.cryptography
 */
@Serializable
@JvmInline public value class SecretBasis(public val string: String) {
    public companion object {
        public const val BITS: Int = 512
        public const val BYTES: Int = BITS / 8
        public const val BASE64_CHARS: Int = 66
    }
    
    /**
     * Creates a new SecretBasis with random bytes.
     */
    public constructor() : this(Base64.encode(Random.nextBytes(BYTES)))
    
    /**
     * Gets the bytes representation of this SecretBasis.
     */
    public val bytes: ByteArray get() = Base64.decode(string).sliceArray(0 until BYTES)
    
    /**
     * Derives a key from this SecretBasis using the provided key.
     * This implementation uses HMAC-SHA512.
     */
    public fun derive(key: String): ByteArray = SecureHasher.HS512(bytes).sign(key.toByteArray())
}

/**
 * Interface for secure hashing operations.
 * 
 * Note: The current implementation uses JVM-specific cryptography libraries.
 * For multiplatform support, this should be refactored to use the dev.whyoleg.cryptography
 * libraries that are already included in the project dependencies.
 */
public interface SecureHasher {
    /**
     * Signs the provided data.
     */
    public fun sign(data: ByteArray): ByteArray
    
    public companion object {
        /**
         * Creates a HMAC-SHA512 hasher with the provided key.
         */
        public fun HS512(key: ByteArray): SecureHasher {
            return object : SecureHasher {
                private val mac = Mac.getInstance("HmacSHA512")
                
                init {
                    mac.init(SecretKeySpec(key, "HmacSHA512"))
                }
                
                override fun sign(data: ByteArray): ByteArray {
                    return mac.doFinal(data)
                }
            }
        }
    }
}

/**
 * Interface for encryption operations.
 * 
 * Note: The current implementation uses JVM-specific cryptography libraries.
 * For multiplatform support, this should be refactored to use the dev.whyoleg.cryptography
 * libraries that are already included in the project dependencies.
 */
public interface Encryptor {
    /**
     * Encrypts the provided data.
     */
    public fun encrypt(data: ByteArray): ByteArray
    
    /**
     * Decrypts the provided data.
     */
    public fun decrypt(data: ByteArray): ByteArray
    
    public companion object {
        /**
         * Creates an AES-CBC-PKCS5Padding encryptor with the provided key.
         */
        public fun AesCbcPkcs5Padding(key: ByteArray): Encryptor {
            require(key.size == 32) { "AES-256 requires a 32-byte key" }
            
            return object : Encryptor {
                // Use 16 bytes (128 bits) for the IV
                private val iv = ByteArray(16) { 0 }
                
                override fun encrypt(data: ByteArray): ByteArray {
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(
                        Cipher.ENCRYPT_MODE,
                        SecretKeySpec(key, "AES"),
                        IvParameterSpec(iv)
                    )
                    return cipher.doFinal(data)
                }
                
                override fun decrypt(data: ByteArray): ByteArray {
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        SecretKeySpec(key, "AES"),
                        IvParameterSpec(iv)
                    )
                    return cipher.doFinal(data)
                }
            }
        }
    }
}

/**
 * Creates a SecureHasher from a SecretBasis and a variant.
 */
public fun SecretBasis.hasher(variant: String): SecureHasher = SecureHasher.HS512(this.derive(variant))

/**
 * Creates an Encryptor from a SecretBasis and a variant.
 */
public fun SecretBasis.encryptor(variant: String): Encryptor = Encryptor.AesCbcPkcs5Padding(this.derive(variant).sliceArray(0 until 32))

/**
 * Creates a function that returns a SecureHasher from a function that returns a SecretBasis and a variant.
 */
public fun (()->SecretBasis).hasher(variant: String): ()->SecureHasher = { this().hasher(variant) }

/**
 * Creates a function that returns an Encryptor from a function that returns a SecretBasis and a variant.
 */
public fun (()->SecretBasis).encryptor(variant: String): ()->Encryptor = { this().encryptor(variant) }