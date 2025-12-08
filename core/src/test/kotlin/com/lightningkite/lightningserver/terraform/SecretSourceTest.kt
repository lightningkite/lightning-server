package com.lightningkite.lightningserver.terraform

import com.lightningkite.services.terraform.TerraformNeed
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Ignore
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Unit tests for SecretSource implementations.
 * Note: Some tests involving EncryptedFileSecretSource are complex due to password prompting
 * and encryption, so they may be marked with @Ignore for CI/CD environments.
 */
class SecretSourceTest {

    private val testNeed = object : TerraformNeed<String> {
        override val name: String = "TEST_SECRET"
        override val serializer: KSerializer<String> = String.serializer()
        override val default: String? = null
        override val instructions: String = "Test secret for unit testing"
    }

    private val testNeedWithDefault = object : TerraformNeed<String> {
        override val name: String = "TEST_WITH_DEFAULT"
        override val serializer: KSerializer<String> = String.serializer()
        override val default: String = "default-value"
        override val instructions: String = "Test secret with default value"
    }

    /** Helper to create a test PasswordFetcher with a predefined password */
    private fun mockPasswordFetcher(password: String): PasswordFetcher {
        return object : PasswordFetcher() {
            private var hasRead = false
            override fun read(prompt: String, verify: (String) -> Unit): String {
                verify(password)  // Run verification
                hasRead = true
                return password
            }
        }
    }

    @Test
    fun `EnvironmentSecretSource returns value from environment`() {
        // This test requires setting environment variables, which is difficult in JUnit
        // We can only test the null case
        val result = EnvironmentSecretSource.getOrNull(testNeed)
        assertNull(result, "Should return null when SECRET_TEST_SECRET is not set")
    }

    @Test
    fun `ManySecretSources returns first non-null value`() {
        val source1 = object : SecretSource {
            override val name: String = "Source1"
            override fun <T> getOrNull(need: TerraformNeed<T>): T? = null
        }
        val source2 = object : SecretSource {
            override val name: String = "Source2"
            override fun <T> getOrNull(need: TerraformNeed<T>): T? = "from-source2" as? T
        }
        val source3 = object : SecretSource {
            override val name: String = "Source3"
            override fun <T> getOrNull(need: TerraformNeed<T>): T? = "from-source3" as? T
        }

        val many = ManySecretSources(source1, source2, source3)
        val result = many.getOrNull(testNeed)
        assertEquals("from-source2", result, "Should return first non-null value")
    }

    @Test
    fun `ManySecretSources returns null when all sources return null`() {
        val source1 = object : SecretSource {
            override val name: String = "Source1"
            override fun <T> getOrNull(need: TerraformNeed<T>): T? = null
        }
        val source2 = object : SecretSource {
            override val name: String = "Source2"
            override fun <T> getOrNull(need: TerraformNeed<T>): T? = null
        }

        val many = ManySecretSources(source1, source2)
        val result = many.getOrNull(testNeed)
        assertNull(result, "Should return null when all sources return null")
    }

    @Test
    fun `SecretSource get() throws when value not found and no default`() {
        val source = object : SecretSource {
            override val name: String = "TestSource"
            override fun <T> getOrNull(need: TerraformNeed<T>): T? = null
        }

        assertFailsWith<IllegalStateException> {
            source.get(testNeed)
        }
    }

    @Test
    fun `EncryptedFileSecretSource creates new file when it doesn't exist`() {
        val tempFile = File.createTempFile("test-secrets", ".json.enc")
        tempFile.delete() // Delete it so we can test creation

        try {
            val source = EncryptedFileSecretSource(tempFile, "test-source", mockPasswordFetcher("test-password-123"))

            // Accessing the source should create the file
            source.set(testNeed, "test-value")

            assertTrue(tempFile.exists(), "File should be created")
            assertTrue(tempFile.length() > 0, "File should not be empty")
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `EncryptedFileSecretSource can store and retrieve values`() {
        val tempFile = File.createTempFile("test-secrets", ".json.enc")
        tempFile.delete() // Delete it so we can test creation

        try {
            val source = EncryptedFileSecretSource(tempFile, "test-source", mockPasswordFetcher("test-password-123"))

            // Store a value
            source.set(testNeed, "my-secret-value")

            // Retrieve it
            val retrieved = source.getOrNull(testNeed)
            assertEquals("my-secret-value", retrieved, "Should retrieve the stored value")
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `EncryptedFileSecretSource persists values across instances`() {
        val tempFile = File.createTempFile("test-secrets", ".json.enc")
        tempFile.delete() // Delete it so we can test creation

        try {
            val password = "test-password-123"

            // Create first instance and store a value
            val source1 = EncryptedFileSecretSource(tempFile, "test-source", mockPasswordFetcher(password))
            source1.set(testNeed, "persistent-value")

            // Create second instance with same password and retrieve
            val source2 = EncryptedFileSecretSource(tempFile, "test-source", mockPasswordFetcher(password))
            val retrieved = source2.getOrNull(testNeed)

            assertEquals("persistent-value", retrieved, "Should retrieve value stored by previous instance")
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `PasswordFetcher clear() functionality exists`() {
        // Simple test to verify the clear method exists and doesn't throw
        val fetcher = PasswordFetcher()
        fetcher.clear()  // Should not throw
    }
}
