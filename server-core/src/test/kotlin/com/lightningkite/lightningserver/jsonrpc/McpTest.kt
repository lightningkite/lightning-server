package com.lightningkite.lightningserver.jsonrpc

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the Model Context Protocol (MCP) implementation.
 */
class McpTest {

    init {
        TestSettings
    }

    /**
     * Tests the ping method of the MCP implementation.
     */
    @Test
    fun testPing() {
        runBlocking {
            // Create a JSON-RPC endpoint with MCP methods
            val path = ServerPath("api/mcp")
            val endpoint = path.jsonRpc<HasId<*>?>()
            endpoint.mcp()

            // Create a JSON-RPC request for the ping method
            val request = JsonRpcRequest(
                method = "ping",
                params = Serialization.json.encodeToJsonElement(Unit),
                id = "1"
            )

            // Execute the request
            val response = path.post.test(
                body = HttpContent.Text(
                    Json.encodeToString(JsonRpcRequest.serializer(), request),
                    ContentType.Application.Json
                )
            )

            // Verify the response
            assertEquals(HttpStatus.OK, response.status)
            assertNotNull(response.body)

            // Parse the response
            val responseText = response.body!!.bytes().decodeToString()
            val jsonRpcResponse = Json.decodeFromString(
                JsonRpcResponse.serializer(),
                responseText
            )

            // Verify the result
            assertEquals("1", jsonRpcResponse.id)
            assertNull(jsonRpcResponse.error)
            assertNotNull(jsonRpcResponse.result)
        }
    }

    /**
     * Tests the notifications/initialized method of the MCP implementation.
     */
    @Test
    fun testNotificationsInitialized() {
        runBlocking {
            // Create a JSON-RPC endpoint with MCP methods
            val path = ServerPath("api/mcp")
            val endpoint = path.jsonRpc<HasId<*>?>()
            endpoint.mcp()

            // Create a JSON-RPC request for the notifications/initialized method
            val request = JsonRpcRequest(
                method = "notifications/initialized",
                params = Serialization.json.encodeToJsonElement(Unit),
                id = "1"
            )

            // Execute the request
            val response = path.post.test(
                body = HttpContent.Text(
                    Json.encodeToString(JsonRpcRequest.serializer(), request),
                    ContentType.Application.Json
                )
            )

            // Verify the response
            assertEquals(HttpStatus.OK, response.status)
            assertNotNull(response.body)

            // Parse the response
            val responseText = response.body!!.bytes().decodeToString()
            val jsonRpcResponse = Json.decodeFromString(
                JsonRpcResponse.serializer(),
                responseText
            )

            // Verify the result
            assertEquals("1", jsonRpcResponse.id)
            assertNull(jsonRpcResponse.error)
            assertNotNull(jsonRpcResponse.result)
        }
    }

    /**
     * Tests the prompts/list method of the MCP implementation.
     */
    @Test
    fun testPromptsList() {
        runBlocking {
            // Create a JSON-RPC endpoint with MCP methods
            val path = ServerPath("api/mcp")
            val endpoint = path.jsonRpc<HasId<*>?>()
            endpoint.mcp()

            // Create a JSON-RPC request for the prompts/list method
            val request = JsonRpcRequest(
                method = "prompts/list",
                params = Serialization.json.encodeToJsonElement(McpListRequest()),
                id = "1"
            )

            // Execute the request
            val response = path.post.test(
                body = HttpContent.Text(
                    Json.encodeToString(JsonRpcRequest.serializer(), request),
                    ContentType.Application.Json
                )
            )

            // Verify the response
            assertEquals(HttpStatus.OK, response.status)
            assertNotNull(response.body)

            // Parse the response
            val responseText = response.body!!.bytes().decodeToString()
            val jsonRpcResponse = Json.decodeFromString(
                JsonRpcResponse.serializer(),
                responseText
            )

            // Verify the result
            assertEquals("1", jsonRpcResponse.id)
            assertNull(jsonRpcResponse.error)
            assertNotNull(jsonRpcResponse.result)

            // Verify the prompts list
            val promptPage = Serialization.json.decodeFromJsonElement<McpPromptPage>(jsonRpcResponse.result!!)
            assertTrue(promptPage.prompts.isNotEmpty())
            assertEquals("example-prompt", promptPage.prompts[0].name)
            assertEquals("Example Prompt", promptPage.prompts[0].title)
            assertTrue(promptPage.prompts[0].arguments.isNotEmpty())
            assertEquals("input", promptPage.prompts[0].arguments[0].name)
            assertTrue(promptPage.prompts[0].arguments[0].required)
        }
    }

    /**
     * Tests the prompts/get method of the MCP implementation.
     */
    @Test
    fun testPromptsGet() {
        runBlocking {
            // Create a JSON-RPC endpoint with MCP methods
            val path = ServerPath("api/mcp")
            val endpoint = path.jsonRpc<HasId<*>?>()
            endpoint.mcp()

            // Create a JSON-RPC request for the prompts/get method
            val request = JsonRpcRequest(
                method = "prompts/get",
                params = Serialization.json.encodeToJsonElement(McpPromptRequest("test-prompt")),
                id = "1"
            )

            // Execute the request
            val response = path.post.test(
                body = HttpContent.Text(
                    Json.encodeToString(JsonRpcRequest.serializer(), request),
                    ContentType.Application.Json
                )
            )

            // Verify the response
            assertEquals(HttpStatus.OK, response.status)
            assertNotNull(response.body)

            // Parse the response
            val responseText = response.body!!.bytes().decodeToString()
            val jsonRpcResponse = Json.decodeFromString(
                JsonRpcResponse.serializer(),
                responseText
            )

            // Verify the result
            assertEquals("1", jsonRpcResponse.id)
            assertNull(jsonRpcResponse.error)
            assertNotNull(jsonRpcResponse.result)

            // Verify the prompt
            val prompt = Serialization.json.decodeFromJsonElement<McpPrompt>(jsonRpcResponse.result!!)
            assertEquals("test-prompt", prompt.name)
            assertEquals("Example Prompt", prompt.title)
            assertTrue(prompt.arguments.isNotEmpty())
            assertEquals("input", prompt.arguments[0].name)
            assertTrue(prompt.arguments[0].required)
        }
    }

    /**
     * Tests the resources/list method of the MCP implementation.
     */
    @Test
    fun testResourcesList() {
        runBlocking {
            // Create a JSON-RPC endpoint with MCP methods
            val path = ServerPath("api/mcp")
            val endpoint = path.jsonRpc<HasId<*>?>()
            endpoint.mcp()

            // Create a JSON-RPC request for the resources/list method
            val request = JsonRpcRequest(
                method = "resources/list",
                params = Serialization.json.encodeToJsonElement(McpResourceListRequest()),
                id = "1"
            )

            // Execute the request
            val response = path.post.test(
                body = HttpContent.Text(
                    Json.encodeToString(JsonRpcRequest.serializer(), request),
                    ContentType.Application.Json
                )
            )

            // Verify the response
            assertEquals(HttpStatus.OK, response.status)
            assertNotNull(response.body)

            // Parse the response
            val responseText = response.body!!.bytes().decodeToString()
            val jsonRpcResponse = Json.decodeFromString(
                JsonRpcResponse.serializer(),
                responseText
            )

            // Verify the result
            assertEquals("1", jsonRpcResponse.id)
            assertNull(jsonRpcResponse.error)
            assertNotNull(jsonRpcResponse.result)

            // Verify the resources list
            val resourceList = Serialization.json.decodeFromJsonElement<McpResourceListResponse>(jsonRpcResponse.result!!)
            assertTrue(resourceList.resources.isNotEmpty())
            assertEquals("resource-1", resourceList.resources[0].id)
            assertEquals("document", resourceList.resources[0].type)
            assertEquals("Example Document", resourceList.resources[0].name)
        }
    }

    /**
     * Tests the resources/get method of the MCP implementation.
     */
    @Test
    fun testResourcesGet() {
        runBlocking {
            // Create a JSON-RPC endpoint with MCP methods
            val path = ServerPath("api/mcp")
            val endpoint = path.jsonRpc<HasId<*>?>()
            endpoint.mcp()

            // Create a JSON-RPC request for the resources/get method
            val request = JsonRpcRequest(
                method = "resources/get",
                params = Serialization.json.encodeToJsonElement(McpResourceRequest("test-resource")),
                id = "1"
            )

            // Execute the request
            val response = path.post.test(
                body = HttpContent.Text(
                    Json.encodeToString(JsonRpcRequest.serializer(), request),
                    ContentType.Application.Json
                )
            )

            // Verify the response
            assertEquals(HttpStatus.OK, response.status)
            assertNotNull(response.body)

            // Parse the response
            val responseText = response.body!!.bytes().decodeToString()
            val jsonRpcResponse = Json.decodeFromString(
                JsonRpcResponse.serializer(),
                responseText
            )

            // Verify the result
            assertEquals("1", jsonRpcResponse.id)
            assertNull(jsonRpcResponse.error)
            assertNotNull(jsonRpcResponse.result)

            // Verify the resource
            val resource = Serialization.json.decodeFromJsonElement<McpResource>(jsonRpcResponse.result!!)
            assertEquals("test-resource", resource.id)
            assertEquals("document", resource.type)
            assertEquals("Example Document", resource.name)
        }
    }

    /**
     * Tests the resources/upload method of the MCP implementation.
     */
    @Test
    fun testResourcesUpload() {
        runBlocking {
            // Create a JSON-RPC endpoint with MCP methods
            val path = ServerPath("api/mcp")
            val endpoint = path.jsonRpc<HasId<*>?>()
            endpoint.mcp()

            // Create a JSON-RPC request for the resources/upload method
            val request = JsonRpcRequest(
                method = "resources/upload",
                params = Serialization.json.encodeToJsonElement(
                    McpResourceUploadRequest(
                        type = "document",
                        name = "Test Document",
                        mimeType = "text/plain"
                    )
                ),
                id = "1"
            )

            // Execute the request
            val response = path.post.test(
                body = HttpContent.Text(
                    Json.encodeToString(JsonRpcRequest.serializer(), request),
                    ContentType.Application.Json
                )
            )

            // Verify the response
            assertEquals(HttpStatus.OK, response.status)
            assertNotNull(response.body)

            // Parse the response
            val responseText = response.body!!.bytes().decodeToString()
            val jsonRpcResponse = Json.decodeFromString(
                JsonRpcResponse.serializer(),
                responseText
            )

            // Verify the result
            assertEquals("1", jsonRpcResponse.id)
            assertNull(jsonRpcResponse.error)
            assertNotNull(jsonRpcResponse.result)

            // Verify the upload response
            val uploadResponse = Serialization.json.decodeFromJsonElement<McpResourceUploadResponse>(jsonRpcResponse.result!!)
            assertNotNull(uploadResponse.id)
            assertNotNull(uploadResponse.uploadUrl)
            assertTrue(uploadResponse.headers.isNotEmpty())
            assertEquals("PUT", uploadResponse.method)
            assertNotNull(uploadResponse.expiresAt)
        }
    }

    /**
     * Tests the resources/download method of the MCP implementation.
     */
    @Test
    fun testResourcesDownload() {
        runBlocking {
            // Create a JSON-RPC endpoint with MCP methods
            val path = ServerPath("api/mcp")
            val endpoint = path.jsonRpc<HasId<*>?>()
            endpoint.mcp()

            // Create a JSON-RPC request for the resources/download method
            val request = JsonRpcRequest(
                method = "resources/download",
                params = Serialization.json.encodeToJsonElement(
                    McpResourceDownloadRequest(
                        id = "test-resource"
                    )
                ),
                id = "1"
            )

            // Execute the request
            val response = path.post.test(
                body = HttpContent.Text(
                    Json.encodeToString(JsonRpcRequest.serializer(), request),
                    ContentType.Application.Json
                )
            )

            // Verify the response
            assertEquals(HttpStatus.OK, response.status)
            assertNotNull(response.body)

            // Parse the response
            val responseText = response.body!!.bytes().decodeToString()
            val jsonRpcResponse = Json.decodeFromString(
                JsonRpcResponse.serializer(),
                responseText
            )

            // Verify the result
            assertEquals("1", jsonRpcResponse.id)
            assertNull(jsonRpcResponse.error)
            assertNotNull(jsonRpcResponse.result)

            // Verify the download response
            val downloadResponse = Serialization.json.decodeFromJsonElement<McpResourceDownloadResponse>(jsonRpcResponse.result!!)
            assertNotNull(downloadResponse.downloadUrl)
            assertTrue(downloadResponse.headers.isNotEmpty())
            assertNotNull(downloadResponse.expiresAt)
        }
    }
}
