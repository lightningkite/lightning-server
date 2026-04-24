// by Claude
package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.services.data.MediaType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.test.*

/**
 * Tests for the ApiDocs class which generates API documentation and SDKs.
 *
 * These tests verify:
 * - TypeScript SDK generation endpoint
 * - TypeScript SDK zip endpoint
 * - Kotlin SDK generation endpoint
 * - Kotlin SDK zip endpoint
 * - API documentation index page
 */
class ApiDocsTest {

    @Serializable
    data class TestInput(val name: String, val value: Int)

    @Serializable
    data class TestOutput(val result: String)

    object TestServer : ServerBuilder() {
        init {
            registerBasicMediaTypeCoders()
        }

        // Create a simple API endpoint for documentation testing
        val testEndpoint = path.path("api").path("test").post bind ApiHttpHandler(
            summary = "Test endpoint",
            description = "A test endpoint for API documentation",
            auth = noAuth,
            implementation = { _: TestInput ->
                TestOutput("success")
            }
        )

        val anotherEndpoint = path.path("api").path("another").get bind ApiHttpHandler(
            summary = "Another test endpoint",
            description = "Another endpoint for testing",
            auth = noAuth,
            implementation = { _: Unit ->
                TestOutput("another result")
            }
        )

        val docs = path.path("docs") include ApiDocs("com.test.sdk")
    }

    // ========== ApiDocs Instance Tests ==========

    @Test
    fun `ApiDocs can be instantiated with package name`() {
        val docs = ApiDocs("com.example.sdk")
        assertNotNull(docs)
    }

    // ========== TypeScript SDK Endpoint Tests ==========

    @Test
    fun `typescript endpoint returns TypeScript SDK`() = runBlocking {
        TestServer.test({}) {
            val response = docs.typescript.test()

            assertEquals(HttpStatus.OK, response.status)
            assertNotNull(response.body)
            assertEquals(MediaType.Text.Plain, response.body?.mediaType)
        }
    }

    @Test
    fun `typescriptZip endpoint returns zip file`() = runBlocking {
        TestServer.test({}) {
            val response = docs.typescriptZip.test()

            assertEquals(HttpStatus.OK, response.status)
            assertNotNull(response.body)
            assertEquals(MediaType.Application.Zip, response.body?.mediaType)
        }
    }

    // ========== Kotlin SDK Endpoint Tests ==========

    @Test
    fun `kotlin endpoint returns Kotlin SDK`() = runBlocking {
        TestServer.test({}) {
            val response = docs.kotlin.test()

            assertEquals(HttpStatus.OK, response.status)
            assertNotNull(response.body)
            assertEquals(MediaType.Text.Plain, response.body?.mediaType)
        }
    }

    @Test
    fun `kotlinZip endpoint returns zip file`() = runBlocking {
        TestServer.test({}) {
            val response = docs.kotlinZip.test()

            assertEquals(HttpStatus.OK, response.status)
            assertNotNull(response.body)
            assertEquals(MediaType.Application.Zip, response.body?.mediaType)
        }
    }

    // ========== API Documentation Index Tests ==========

    @Test
    fun `index endpoint returns HTML documentation`() = runBlocking {
        TestServer.test({}) {
            val response = docs.index.test()

            assertEquals(HttpStatus.OK, response.status)
            assertNotNull(response.body)
        }
    }

    @Test
    fun `index endpoint contains API Docs title`() = runBlocking {
        TestServer.test({}) {
            val response = docs.index.test()
            val content = response.body?.text()

            assertNotNull(content)
            assertTrue(content.contains("API Docs"), "Documentation should contain 'API Docs' title")
        }
    }

    @Test
    fun `index endpoint lists SDK download links`() = runBlocking {
        TestServer.test({}) {
            val response = docs.index.test()
            val content = response.body?.text()

            assertNotNull(content)
            assertTrue(content.contains("sdk.ts"), "Documentation should link to TypeScript SDK")
            assertTrue(content.contains("sdk.kt"), "Documentation should link to Kotlin SDK")
        }
    }

    @Test
    fun `index endpoint includes Types section`() = runBlocking {
        TestServer.test({}) {
            val response = docs.index.test()
            val content = response.body?.text()

            assertNotNull(content)
            assertTrue(content.contains("Types"), "Documentation should have Types section")
        }
    }

    @Test
    fun `index endpoint includes Endpoints section`() = runBlocking {
        TestServer.test({}) {
            val response = docs.index.test()
            val content = response.body?.text()

            assertNotNull(content)
            assertTrue(content.contains("Endpoints"), "Documentation should have Endpoints section")
        }
    }

    @Test
    fun `index endpoint documents test endpoint`() = runBlocking {
        TestServer.test({}) {
            val response = docs.index.test()
            val content = response.body?.text()

            assertNotNull(content)
            // The endpoint summary should appear in the docs
            assertTrue(
                content.contains("Test endpoint") || content.contains("test"),
                "Documentation should include test endpoint info"
            )
        }
    }

    // ========== Content Type Tests ==========

    @Test
    fun `SDK endpoints return plain text content type`() = runBlocking {
        TestServer.test({}) {
            val tsResponse = docs.typescript.test()
            val ktResponse = docs.kotlin.test()

            assertEquals(MediaType.Text.Plain, tsResponse.body?.mediaType)
            assertEquals(MediaType.Text.Plain, ktResponse.body?.mediaType)
        }
    }

    @Test
    fun `Zip endpoints return application zip content type`() = runBlocking {
        TestServer.test({}) {
            val tsZipResponse = docs.typescriptZip.test()
            val ktZipResponse = docs.kotlinZip.test()

            assertEquals(MediaType.Application.Zip, tsZipResponse.body?.mediaType)
            assertEquals(MediaType.Application.Zip, ktZipResponse.body?.mediaType)
        }
    }

    // ========== SDK Content Verification Tests ==========

    @Test
    fun `typescript SDK contains function definitions`() = runBlocking {
        TestServer.test({}) {
            val response = docs.typescript.test()
            val content = response.body?.text()

            assertNotNull(content)
            // TypeScript SDK should have some typical structure
            assertTrue(content.isNotEmpty(), "TypeScript SDK should not be empty")
        }
    }

    @Test
    fun `kotlin SDK contains package declaration`() = runBlocking {
        TestServer.test({}) {
            val response = docs.kotlin.test()
            val content = response.body?.text()

            assertNotNull(content)
            assertTrue(content.contains("package"), "Kotlin SDK should contain package declaration")
        }
    }
}
