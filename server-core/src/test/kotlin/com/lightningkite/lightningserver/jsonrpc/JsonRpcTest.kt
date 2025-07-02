package com.lightningkite.lightningserver.jsonrpc

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.auth.RequestAuth
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JsonRpcTest {
    
    @Serializable
    data class TestParams(val a: Int, val b: Int)
    
    @Serializable
    data class TestResult(val sum: Int)

    init {
        TestSettings
    }
    
    @Test
    fun testBasicJsonRpcCall() = runBlocking {
        // Create a JSON-RPC endpoint
        val path = ServerPath("api/jsonrpc")
        val endpoint = path.jsonRpc<HasId<*>?>()
        
        // Register a method
        endpoint.registerMethod<TestParams, TestResult>("add") { params ->
            TestResult(params.a + params.b)
        }
        
        // Create a JSON-RPC request
        val request = JsonRpcRequest(
            method = "add",
            params = Serialization.json.encodeToJsonElement(TestParams(2, 3)),
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
        assertEquals(5, Serialization.json.decodeFromJsonElement<TestResult>(jsonRpcResponse.result).sum)
    }
    
    @Test
    fun testMethodNotFound() = runBlocking {
        // Create a JSON-RPC endpoint
        val path = ServerPath("api/jsonrpc")
        val endpoint = path.jsonRpc<HasId<*>?>()
        
        // Create a JSON-RPC request with a non-existent method
        val request = JsonRpcRequest(
            method = "nonExistentMethod",
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
        
        // Verify the error
        assertEquals("1", jsonRpcResponse.id)
        assertNull(jsonRpcResponse.result)
        assertNotNull(jsonRpcResponse.error)
        assertEquals(JsonRpcError.METHOD_NOT_FOUND, jsonRpcResponse.error?.code)
    }
    
    @Test
    fun testNotification() = runBlocking {
        // Create a JSON-RPC endpoint
        val path = ServerPath("api/jsonrpc")
        val endpoint = path.jsonRpc<HasId<*>?>()
        
        // Register a method
        var methodCalled = false
        endpoint.registerMethod<TestParams, Unit>("notify") { _ ->
            methodCalled = true
        }
        
        // Create a JSON-RPC notification (request with no id)
        val request = JsonRpcRequest(
            method = "notify",
            params = Serialization.json.encodeToJsonElement(TestParams(1, 2)),
            id = null
        )
        
        // Execute the request
        val response = path.post.test(
            body = HttpContent.Text(
                Json.encodeToString(JsonRpcRequest.serializer(), request),
                ContentType.Application.Json
            )
        )
        
        // Verify the response
        assertEquals(HttpStatus.NoContent, response.status)
        
        // Verify the method was called
        assertEquals(true, methodCalled)
    }
    
    @Test
    fun testBatchRequest() = runBlocking {
        // Create a JSON-RPC endpoint
        val path = ServerPath("api/jsonrpc")
        val endpoint = path.jsonRpc<HasId<*>?>()
        
        // Register methods
        endpoint.registerMethod<TestParams, TestResult>("add") { params ->
            TestResult(params.a + params.b)
        }
        
        endpoint.registerMethod<TestParams, TestResult>("multiply") { params ->
            TestResult(params.a * params.b)
        }
        
        // Create a batch of JSON-RPC requests
        val batchJson = """
            [
                {"jsonrpc": "2.0", "method": "add", "params": {"a": 2, "b": 3}, "id": "1"},
                {"jsonrpc": "2.0", "method": "multiply", "params": {"a": 2, "b": 3}, "id": "2"}
            ]
        """.trimIndent()
        
        // Execute the request
        val response = path.post.test(
            body = HttpContent.Text(
                batchJson,
                ContentType.Application.Json
            )
        )
        
        // Verify the response
        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body)
        
        // Parse the response as a JSON array
        val responseText = response.body!!.bytes().decodeToString()
        val responseArray = Json.parseToJsonElement(responseText).jsonArray
        
        // Verify we got 2 responses
        assertEquals(2, responseArray.size)
        
        // Verify the first response (add)
        val addResponse = responseArray[0].jsonObject
        assertEquals("1", addResponse["id"]?.jsonPrimitive?.content)
        assertEquals(5, addResponse["result"]?.jsonObject?.get("sum")?.jsonPrimitive?.int)
        
        // Verify the second response (multiply)
        val multiplyResponse = responseArray[1].jsonObject
        assertEquals("2", multiplyResponse["id"]?.jsonPrimitive?.content)
        assertEquals(6, multiplyResponse["result"]?.jsonObject?.get("sum")?.jsonPrimitive?.int)
    }
}