package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.runtime.test.test
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NullablePrimitiveTest {

    object TestServer : ServerBuilder() {
        // Test nullable primitive as input
        val testNullableIntInput = path.path("test-nullable-int").post bind ApiHttpHandler(
            summary = "Test Nullable Int Input",
            auth = noAuth,
            implementation = { input: Int? ->
                "Received: ${input ?: "null"}"
            }
        )

        // Test nullable primitive as output
        val testNullableStringOutput = path.path("test-nullable-string").get bind ApiHttpHandler(
            summary = "Test Nullable String Output",
            auth = noAuth,
            implementation = { _: Unit ->
                val result: String? = null
                result
            }
        )

        val testNonNullStringOutput = path.path("test-non-null-string").get bind ApiHttpHandler(
            summary = "Test Non-Null String Output",
            auth = noAuth,
            implementation = { _: Unit ->
                val result: String? = "value"
                result
            }
        )

        // Test nullable primitive both input and output
        val testNullableDouble = path.path("test-nullable-double").post bind ApiHttpHandler(
            summary = "Test Nullable Double",
            auth = noAuth,
            implementation = { input: Double? ->
                val result: Double? = input?.times(2)
                result
            }
        )

        // Test nullable Boolean
        val testNullableBoolean = path.path("test-nullable-boolean").post bind ApiHttpHandler(
            summary = "Test Nullable Boolean",
            auth = noAuth,
            implementation = { input: Boolean? ->
                val result: Boolean? = input?.not()
                result
            }
        )
    }

    @Test
    fun testNullableIntInput() = runBlocking {
        TestServer.test({}) {
            // Test with non-null value
            val result1 = TestServer.testNullableIntInput.test(null, 42)
            assertEquals("Received: 42", result1)

            // Test with null value
            val result2 = TestServer.testNullableIntInput.test(null, null)
            assertEquals("Received: null", result2)
        }
    }

    @Test
    fun testNullableStringOutput() = runBlocking {
        TestServer.test({}) {
            // Test null output
            val result = TestServer.testNullableStringOutput.test(null, Unit)
            assertNull(result)
        }
    }

    @Test
    fun testNonNullStringOutput() = runBlocking {
        TestServer.test({}) {
            // Test non-null output
            val result = TestServer.testNonNullStringOutput.test(null, Unit)
            assertEquals("value", result)
        }
    }

    @Test
    fun testNullableDouble() = runBlocking {
        TestServer.test({}) {
            // Test with non-null value
            val result1 = TestServer.testNullableDouble.test(null, 3.14)
            assertEquals(6.28, result1!!, 0.001)

            // Test with null value
            val result2 = TestServer.testNullableDouble.test(null, null)
            assertNull(result2)
        }
    }

    @Test
    fun testNullableBoolean() = runBlocking {
        TestServer.test({}) {
            // Test with true
            val result1 = TestServer.testNullableBoolean.test(null, true)
            assertEquals(false, result1)

            // Test with false
            val result2 = TestServer.testNullableBoolean.test(null, false)
            assertEquals(true, result2)

            // Test with null
            val result3 = TestServer.testNullableBoolean.test(null, null)
            assertNull(result3)
        }
    }
}
