package com.lightningkite.lightningserver.typed.jsonrpc

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.services.data.TypedData
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class JsonRpcHandlerTest {

    @Serializable
    data class AddParams(
        val a: Int,
        val b: Int
    )

    @Serializable
    data class GreetParams(
        val name: String
    )

    @Serializable
    data class GreetResult(
        val message: String
    )

    @Serializable
    data class ComplexData(
        val id: String,
        val values: List<Int>,
        val metadata: Map<String, String>
    )

    object TestServer : ServerBuilder() {
        init {
            registerBasicMediaTypeCoders()
        }

        // Define RPC methods
        val addMethod = JsonRpcMethod<PathSpec0, Nothing?, AddParams, Int>(
            name = "add",
            description = "Adds two numbers",
            auth = noAuth,
            implementation = { params ->
                params.a + params.b
            }
        )

        val greetMethod = JsonRpcMethod<PathSpec0, Nothing?, GreetParams, GreetResult>(
            name = "greet",
            description = "Greets a person",
            auth = noAuth,
            implementation = { params ->
                GreetResult("Hello, ${params.name}!")
            }
        )

        val complexMethod = JsonRpcMethod<PathSpec0, Nothing?, ComplexData, ComplexData>(
            name = "processComplex",
            description = "Processes complex data",
            auth = noAuth,
            implementation = { data ->
                data.copy(
                    values = data.values.map { it * 2 },
                    metadata = data.metadata + ("processed" to "true")
                )
            }
        )

        val nullableMethod = JsonRpcMethod<PathSpec0, Nothing?, String?, String?>(
            name = "maybeUpper",
            description = "Converts to uppercase if not null",
            auth = noAuth,
            implementation = { input ->
                input?.uppercase()
            }
        )

        // Create the JSON-RPC endpoint
        val rpcEndpoint = path.path("rpc").post bind JsonRpcHandler(
            methods = listOf(
                addMethod,
                greetMethod,
                complexMethod,
                nullableMethod
            )
        )
    }

    @Test
    fun testAddMethod() = runBlocking {
        TestServer.test({}) {
            val response = TestServer.rpcEndpoint.test(
                body = TypedData.text("""
                    {
                        "jsonrpc": "2.0",
                        "method": "add",
                        "params": {"a": 5, "b": 3},
                        "id": 1
                    }
                """.trimIndent(), MediaType.Application.Json)
            )

            assertEquals(HttpStatus.OK, response.status)
            val jsonResponse = Json.decodeFromString<JsonRpcResponse>(response.body!!.text())
            assertEquals("2.0", jsonResponse.jsonrpc)
            assertEquals(JsonPrimitive(8), jsonResponse.result)
            assertEquals(JsonPrimitive(1), jsonResponse.id)
        }
    }

    @Test
    fun testGreetMethod() = runBlocking {
        TestServer.test({}) {
            val response = TestServer.rpcEndpoint.test(
                body = TypedData.text("""
                    {
                        "jsonrpc": "2.0",
                        "method": "greet",
                        "params": {"name": "Alice"},
                        "id": 2
                    }
                """.trimIndent(), MediaType.Application.Json)
            )

            assertEquals(HttpStatus.OK, response.status)
            val jsonResponse = Json.decodeFromString<JsonRpcResponse>(response.body!!.text())
            val result = Json.decodeFromJsonElement(GreetResult.serializer(), jsonResponse.result)
            assertEquals("Hello, Alice!", result.message)
        }
    }

    @Test
    fun testComplexDataMethod() = runBlocking {
        TestServer.test({}) {
            val response = TestServer.rpcEndpoint.test(
                body = TypedData.text("""
                    {
                        "jsonrpc": "2.0",
                        "method": "processComplex",
                        "params": {
                            "id": "test-123",
                            "values": [1, 2, 3],
                            "metadata": {"key1": "value1"}
                        },
                        "id": 3
                    }
                """.trimIndent(), MediaType.Application.Json)
            )

            assertEquals(HttpStatus.OK, response.status)
            val jsonResponse = Json.decodeFromString<JsonRpcResponse>(response.body!!.text())
            val result = Json.decodeFromJsonElement(ComplexData.serializer(), jsonResponse.result)
            assertEquals("test-123", result.id)
            assertEquals(listOf(2, 4, 6), result.values)
            assertEquals("value1", result.metadata["key1"])
            assertEquals("true", result.metadata["processed"])
        }
    }

    @Test
    fun testNullableMethodWithValue() = runBlocking {
        TestServer.test({}) {
            val response = TestServer.rpcEndpoint.test(
                body = TypedData.text("""
                    {
                        "jsonrpc": "2.0",
                        "method": "maybeUpper",
                        "params": "hello",
                        "id": 4
                    }
                """.trimIndent(), MediaType.Application.Json)
            )

            assertEquals(HttpStatus.OK, response.status)
            val jsonResponse = Json.decodeFromString<JsonRpcResponse>(response.body!!.text())
            assertEquals(JsonPrimitive("HELLO"), jsonResponse.result)
        }
    }

    @Test
    fun testNullableMethodWithNull() = runBlocking {
        TestServer.test({}) {
            val response = TestServer.rpcEndpoint.test(
                body = TypedData.text("""
                    {
                        "jsonrpc": "2.0",
                        "method": "maybeUpper",
                        "params": null,
                        "id": 5
                    }
                """.trimIndent(), MediaType.Application.Json)
            )

            assertEquals(HttpStatus.OK, response.status)
            val jsonResponse = Json.decodeFromString<JsonRpcResponse>(response.body!!.text())
            assertEquals(JsonNull, jsonResponse.result)
        }
    }

    @Test
    fun testMethodNotFound() = runBlocking {
        TestServer.test({}) {
            val response = TestServer.rpcEndpoint.test(
                body = TypedData.text("""
                    {
                        "jsonrpc": "2.0",
                        "method": "nonexistent",
                        "params": {},
                        "id": 6
                    }
                """.trimIndent(), MediaType.Application.Json)
            )

            assertEquals(HttpStatus.OK, response.status)
            val errorResponse = Json.decodeFromString<JsonRpcErrorResponse>(response.body!!.text())
            assertEquals("2.0", errorResponse.jsonrpc)
            assertEquals(JsonRpcError.METHOD_NOT_FOUND, errorResponse.error.code)
            assertTrue(errorResponse.error.message.contains("nonexistent"))
        }
    }

    @Test
    fun testInvalidParams() = runBlocking {
        TestServer.test({}) {
            val response = TestServer.rpcEndpoint.test(
                body = TypedData.text("""
                    {
                        "jsonrpc": "2.0",
                        "method": "add",
                        "params": {"a": "not a number", "b": 3},
                        "id": 7
                    }
                """.trimIndent(), MediaType.Application.Json)
            )

            assertEquals(HttpStatus.OK, response.status)
            val errorResponse = Json.decodeFromString<JsonRpcErrorResponse>(response.body!!.text())
            assertEquals(JsonRpcError.INVALID_PARAMS, errorResponse.error.code)
        }
    }

    @Test
    fun testInvalidJsonRpcVersion() = runBlocking {
        TestServer.test({}) {
            val response = TestServer.rpcEndpoint.test(
                body = TypedData.text("""
                    {
                        "jsonrpc": "1.0",
                        "method": "add",
                        "params": {"a": 1, "b": 2},
                        "id": 8
                    }
                """.trimIndent(), MediaType.Application.Json)
            )

            assertEquals(HttpStatus.OK, response.status)
            val errorResponse = Json.decodeFromString<JsonRpcErrorResponse>(response.body!!.text())
            assertEquals(JsonRpcError.INVALID_REQUEST, errorResponse.error.code)
            assertTrue(errorResponse.error.message.contains("Invalid jsonrpc version"))
        }
    }

    @Test
    fun testParseError() = runBlocking {
        TestServer.test({}) {
            val response = TestServer.rpcEndpoint.test(
                body = TypedData.text("""
                    {invalid json
                """.trimIndent(), MediaType.Application.Json)
            )

            assertEquals(HttpStatus.OK, response.status)
            val errorResponse = Json.decodeFromString<JsonRpcErrorResponse>(response.body!!.text())
            assertEquals(JsonRpcError.PARSE_ERROR, errorResponse.error.code)
        }
    }

    @Test
    fun testNoRequestBody() = runBlocking {
        TestServer.test({}) {
            val response = TestServer.rpcEndpoint.test(
                body = null
            )

            assertEquals(HttpStatus.OK, response.status)
            val errorResponse = Json.decodeFromString<JsonRpcErrorResponse>(response.body!!.text())
            assertEquals(JsonRpcError.INVALID_REQUEST, errorResponse.error.code)
        }
    }

    @Test
    fun testIdPreservation() = runBlocking {
        TestServer.test({}) {
            // Test with string ID
            val response1 = TestServer.rpcEndpoint.test(
                body = TypedData.text("""
                    {
                        "jsonrpc": "2.0",
                        "method": "add",
                        "params": {"a": 1, "b": 2},
                        "id": "my-string-id"
                    }
                """.trimIndent(), MediaType.Application.Json)
            )

            val jsonResponse1 = Json.decodeFromString<JsonRpcResponse>(response1.body!!.text())
            assertEquals(JsonPrimitive("my-string-id"), jsonResponse1.id)

            // Test with null ID (notification)
            val response2 = TestServer.rpcEndpoint.test(
                body = TypedData.text("""
                    {
                        "jsonrpc": "2.0",
                        "method": "add",
                        "params": {"a": 1, "b": 2}
                    }
                """.trimIndent(), MediaType.Application.Json)
            )

            val jsonResponse2 = Json.decodeFromString<JsonRpcResponse>(response2.body!!.text())
            assertTrue(jsonResponse2.id == null || jsonResponse2.id == JsonNull)
        }
    }
}
