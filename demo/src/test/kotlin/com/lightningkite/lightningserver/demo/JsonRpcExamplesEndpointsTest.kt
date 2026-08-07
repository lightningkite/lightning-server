package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.demo.endpoints.JsonRpcExamplesEndpoints
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.runtime.test.TestRunner
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.typed.jsonrpc.JsonRpcErrorResponse
import com.lightningkite.lightningserver.typed.jsonrpc.JsonRpcResponse
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonRpcExamplesEndpointsTest {

    context(test: TestRunner<*>)
    private suspend fun call(body: String) =
        Server.jsonRpcExamples.rpcEndpoint.test(body = TypedData.text(body, MediaType.Application.Json))

    @Test
    fun mathAddReturnsTheSum() = runBlocking {
        TestHelper.testServer {
            val response = call(
                """{"jsonrpc":"2.0","method":"math.add","params":{"a":5,"b":3},"id":1}"""
            )
            assertEquals(HttpStatus.OK, response.status)
            val rpc = Json.decodeFromString<JsonRpcResponse>(response.body!!.text())
            assertEquals(JsonPrimitive(8.0), rpc.result)
        }
    }

    @Test
    fun mathDivideByZeroReturnsAnRpcError() = runBlocking {
        TestHelper.testServer {
            val response = call(
                """{"jsonrpc":"2.0","method":"math.divide","params":{"a":1,"b":0},"id":2}"""
            )
            val rpc = Json.decodeFromString<JsonRpcErrorResponse>(response.body!!.text())
            assertTrue(rpc.error.message.contains("divide", ignoreCase = true), "unexpected error: ${rpc.error}")
        }
    }

    @Test
    fun echoRepeatsTheMessage() = runBlocking {
        TestHelper.testServer {
            val response = call(
                """{"jsonrpc":"2.0","method":"echo","params":{"message":"hi","times":3},"id":3}"""
            )
            val rpc = Json.decodeFromString<JsonRpcResponse>(response.body!!.text())
            assertEquals(JsonPrimitive("hi hi hi"), rpc.result)
        }
    }

    @Test
    fun getTimeReturnsATimestampAndIsoString() = runBlocking {
        TestHelper.testServer {
            val response = call(
                """{"jsonrpc":"2.0","method":"getTime","params":null,"id":4}"""
            )
            val rpc = Json.decodeFromString<JsonRpcResponse>(response.body!!.text())
            val result = rpc.result!!.jsonObject
            assertTrue(result["timestamp"]!!.jsonPrimitive.long > 0)
            assertTrue(result["iso"]!!.jsonPrimitive.content.isNotBlank())
        }
    }

    @Test
    fun greetReturnsAGreeting() = runBlocking {
        TestHelper.testServer {
            val response = call(
                """{"jsonrpc":"2.0","method":"greet","params":"Ada","id":5}"""
            )
            val rpc = Json.decodeFromString<JsonRpcResponse>(response.body!!.text())
            assertEquals(JsonPrimitive("Hello, Ada!"), rpc.result)
        }
    }
}
