package com.lightningkite.lightningserver.encryption

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.operations.Cipher
import kotlinx.coroutines.runBlocking
import org.junit.Test
import javax.crypto.AEADBadTagException
import kotlin.random.Random

class CipherTests {
    fun assertContentEquals(expected: ByteArray, actual: ByteArray, lazyMessage: (() -> Any)? = null) =
        if (lazyMessage == null) assert(expected contentEquals actual)
        else assert(expected contentEquals actual, lazyMessage)

    fun assertContentNotEquals(expected: ByteArray, actual: ByteArray, lazyMessage: (() -> Any)? = null) =
        if (lazyMessage == null) assert(!expected.contentEquals(actual))
        else assert(!expected.contentEquals(actual), lazyMessage)


    @Test
    fun basicCipherFunctionality(): Unit = runBlocking {
        val provider = CryptographyProvider.Default
        val aesGcm = provider.get(AES.GCM)

        val keyGenerator = aesGcm.keyGenerator(keySize = AES.Key.Size.B256)

        val key: AES.GCM.Key = keyGenerator.generateKey()

        val data = "text".encodeToByteArray()

        val cipher = key.cipher()
        val ciphertext: ByteArray = cipher.encrypt(data)

        val encodedKey: ByteArray = key.encodeToByteArray(AES.Key.Format.RAW)
        val decodedKey: AES.GCM.Key = aesGcm.keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, encodedKey)

        val decodedKeyCipher = decodedKey.cipher()

        assertContentEquals(data, decodedKeyCipher.decrypt(ciphertext))
    }


    private suspend fun Cipher.test(iterations: Int = 100) {
        repeat(iterations) {
            val data = Random.nextBytes(32)
            val encrypted = encrypt(data)
            val decrypted = decrypt(encrypted)
            assertContentEquals(data, decrypted)
        }
    }

    private val basis = SecretBasis()

    @Test
    fun secretBasisCiphers(): Unit = runBlocking {
        repeat(100) {
            println("Test $it")
            basis.cipher(it.toString()).test()
        }
    }

    @Test
    fun variantsAreUnique(): Unit = runBlocking {
        repeat(100) {
            val cipher1 = basis.cipher("cipher1:$it")
            val cipher2 = basis.cipher("cipher2:$it")

            cipher1.test(10)
            cipher2.test(10)

            repeat(50) {
                val data = Random.nextBytes(Random.nextInt(8, 64))

                val e1 = cipher1.encrypt(data)
                try {
                    assertContentNotEquals(data, cipher2.decrypt(e1)) { "c2 decrypted c1" }
                } catch (_: AEADBadTagException) {
                    /*Decryption error from mismatched keys*/
                }

                val e2 = cipher2.encrypt(data)
                try {
                    assertContentNotEquals(data, cipher1.decrypt(e2)) { "c1 decrypted c2" }
                } catch (_: AEADBadTagException) {
                    /*Decryption error from mismatched keys*/
                }
            }
        }
    }
}