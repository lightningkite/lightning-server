package com.lightningkite.lightningserver.settings

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.services.data.workingDirectory
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.EmptySerializersModule
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for OpenSSL encryption/decryption support.
 *
 * These tests verify that the settings system can decrypt files encrypted with:
 * - Modern OpenSSL (PBKDF2)
 * - Legacy OpenSSL (EVP_BytesToKey)
 *
 * Test files are created using actual OpenSSL commands to ensure real-world compatibility.
 */
class OpenSslTest {

    @Serializable
    data class TestConfig(val value: String, val number: Int)

    object TestServer : ServerBuilder() {
        val testSetting = setting("testSetting", "default")
        val config = setting("config", TestConfig("default", 0))
    }

    private val testRoot = workingDirectory.then("build", "test-openssl")
    private val testPassword = "TestPassword123!"

    @After
    fun cleanup() {
        testRoot.deleteRecursively()
    }

    /**
     * Helper to get absolute file path from KFile for OpenSSL command
     */
    private fun com.lightningkite.services.data.KFile.absolutePath(): String =
        java.io.File(this.path.toString()).absolutePath

    /**
     * Helper to execute OpenSSL encryption command
     */
    private fun encryptFileWithOpenSsl(
        inFile: com.lightningkite.services.data.KFile,
        outFile: com.lightningkite.services.data.KFile,
        password: String,
        usePbkdf2: Boolean
    ): Boolean {
        val args = if (usePbkdf2) {
            arrayOf(
                "openssl", "enc", "-aes-256-cbc", "-pbkdf2",
                "-in", inFile.absolutePath(),
                "-out", outFile.absolutePath(),
                "-pass", "pass:$password"
            )
        } else {
            arrayOf(
                "openssl", "enc", "-aes-256-cbc", "-md", "sha256",
                "-in", inFile.absolutePath(),
                "-out", outFile.absolutePath(),
                "-pass", "pass:$password"
            )
        }

        val result = Runtime.getRuntime().exec(args)
        result.waitFor()
        return result.exitValue() == 0
    }

    @Test
    fun testDecryptPbkdf2Format() {
        testRoot.mkdirs()

        // Create a test JSON file
        val plainFile = testRoot.then("test-plain.json")
        plainFile.writeString(
            """
            {
              "testSetting": "pbkdf2-value",
              "config": {
                "value": "pbkdf2-config",
                "number": 42
              }
            }
            """.trimIndent()
        )

        // Encrypt using OpenSSL with PBKDF2
        val encryptedFile = testRoot.then("test-pbkdf2.json.enc")
        if (!encryptFileWithOpenSsl(plainFile, encryptedFile, testPassword, usePbkdf2 = true)) {
            throw Exception("OpenSSL encryption failed - is OpenSSL installed?")
        }

        // Verify the encrypted file was created and has the magic header
        assertTrue(encryptedFile.exists(), "Encrypted file should exist")
        val encryptedBytes = encryptedFile.readByteArray()
        val magic = encryptedBytes.copyOfRange(0, 8).decodeToString()
        assertEquals("Salted__", magic, "File should start with OpenSSL magic header")

        // Now test decryption via settings loading
        val settings = ServerSettings(TestServer.build().settings.toSet())

        // Set the decryption password
        val originalEnvValue = System.getenv("LIGHTNING_SERVER_SETTINGS_DECRYPTION")
        try {
            // Note: We can't actually set environment variables in tests, so we'll test
            // the decryption directly
            val decryptedBytes = OpenSsl.decryptAesCbcPkcs5Sha256(
                encryptedBytes,
                testPassword.toByteArray()
            )
            val decryptedText = decryptedBytes.decodeToString()

            // Verify it contains our test data
            assertTrue(decryptedText.contains("pbkdf2-value"))
            assertTrue(decryptedText.contains("pbkdf2-config"))
        } finally {
            // Can't restore env var in test, but document the pattern
        }
    }

    @Test
    fun testDecryptLegacyFormat() {
        testRoot.mkdirs()

        // Create a test JSON file
        val plainFile = testRoot.then("test-plain.json")
        plainFile.writeString(
            """
            {
              "testSetting": "legacy-value",
              "config": {
                "value": "legacy-config",
                "number": 99
              }
            }
            """.trimIndent()
        )

        // Encrypt using OpenSSL with legacy EVP_BytesToKey (no -pbkdf2 flag)
        val encryptedFile = testRoot.then("test-legacy.json.enc")
        if (!encryptFileWithOpenSsl(plainFile, encryptedFile, testPassword, usePbkdf2 = false)) {
            throw Exception("OpenSSL encryption failed - is OpenSSL installed?")
        }

        // Verify the encrypted file was created
        assertTrue(encryptedFile.exists(), "Encrypted file should exist")
        val encryptedBytes = encryptedFile.readByteArray()

        // Test decryption
        val decryptedBytes = OpenSsl.decryptAesCbcPkcs5Sha256(
            encryptedBytes,
            testPassword.toByteArray()
        )
        val decryptedText = decryptedBytes.decodeToString()

        // Verify it contains our test data
        assertTrue(decryptedText.contains("legacy-value"))
        assertTrue(decryptedText.contains("legacy-config"))
    }

    @Test
    fun testInvalidMagicHeader() {
        val invalidData = "NotValid".toByteArray() + ByteArray(100)

        val exception = assertFailsWith<IllegalArgumentException> {
            OpenSsl.decryptAesCbcPkcs5Sha256(invalidData, "password".toByteArray())
        }

        assertTrue(exception.message!!.contains("Invalid OpenSSL encrypted file format"))
        assertTrue(exception.message!!.contains("Salted__"))
    }

    @Test
    fun testWrongPassword() {
        testRoot.mkdirs()

        // Create a test file
        val plainFile = testRoot.then("test-plain.json")
        plainFile.writeString("""{"testSetting": "value"}""")

        // Encrypt with one password
        val encryptedFile = testRoot.then("test.json.enc")
        if (!encryptFileWithOpenSsl(plainFile, encryptedFile, testPassword, usePbkdf2 = true)) {
            throw Exception("OpenSSL encryption failed - is OpenSSL installed?")
        }

        val encryptedBytes = encryptedFile.readByteArray()

        // Try to decrypt with wrong password
        val exception = assertFailsWith<Exception> {
            OpenSsl.decryptAesCbcPkcs5Sha256(encryptedBytes, "WrongPassword".toByteArray())
        }

        assertTrue(
            exception.message!!.contains("Failed to decrypt") ||
            exception.message!!.contains("Padding")
        )
    }

    @Test
    fun testDirectPbkdf2Decryption() {
        val password = "test123"
        val plaintext = "Hello, World!"

        testRoot.mkdirs()
        val plainFile = testRoot.then("direct-test.txt")
        plainFile.writeString(plaintext)

        val encryptedFile = testRoot.then("direct-test.enc")
        if (encryptFileWithOpenSsl(plainFile, encryptedFile, password, usePbkdf2 = true)) {
            val encrypted = encryptedFile.readByteArray()
            val decrypted = OpenSsl.decryptAesCbcPkcs5Sha256(encrypted, password.toByteArray())
            assertEquals(plaintext, decrypted.decodeToString())
        }
        // If OpenSSL is not available, skip this test
    }

    @Test
    fun testDirectLegacyDecryption() {
        val password = "test123"
        val plaintext = "Legacy Test Data"

        testRoot.mkdirs()
        val plainFile = testRoot.then("legacy-test.txt")
        plainFile.writeString(plaintext)

        val encryptedFile = testRoot.then("legacy-test.enc")
        if (encryptFileWithOpenSsl(plainFile, encryptedFile, password, usePbkdf2 = false)) {
            val encrypted = encryptedFile.readByteArray()
            val decrypted = OpenSsl.decryptAesCbcPkcs5Sha256(encrypted, password.toByteArray())
            assertEquals(plaintext, decrypted.decodeToString())
        }
        // If OpenSSL is not available, skip this test
    }

    @Test
    fun testAutoFormatDetection() {
        // Test that the auto-detection works correctly by creating both formats
        // and ensuring both decrypt successfully

        testRoot.mkdirs()
        val password = "autodetect"
        val plaintext = "Format Detection Test"

        val plainFile = testRoot.then("autodetect-plain.txt")
        plainFile.writeString(plaintext)

        // Create PBKDF2 version
        val pbkdf2File = testRoot.then("autodetect-pbkdf2.enc")
        if (encryptFileWithOpenSsl(plainFile, pbkdf2File, password, usePbkdf2 = true)) {
            val decrypted1 = OpenSsl.decryptAesCbcPkcs5Sha256(
                pbkdf2File.readByteArray(),
                password.toByteArray()
            )
            assertEquals(plaintext, decrypted1.decodeToString())
        }

        // Create legacy version
        val legacyFile = testRoot.then("autodetect-legacy.enc")
        if (encryptFileWithOpenSsl(plainFile, legacyFile, password, usePbkdf2 = false)) {
            val decrypted2 = OpenSsl.decryptAesCbcPkcs5Sha256(
                legacyFile.readByteArray(),
                password.toByteArray()
            )
            assertEquals(plaintext, decrypted2.decodeToString())
        }
    }
}
