package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.demo.endpoints.*
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.typed.test
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TypedApiTest {

    @Test
    fun testCalculatorAddition() = runBlocking {
        TestHelper.testServer {
            val input = CalculatorRequest(10.0, 5.0, "+")
            val result = Server.typedApi.calculator.test(null, input)

            assertNotNull(result)
            assertEquals(15.0, result.result, 0.001)
            assertTrue(result.operation.contains("10.0"))
            assertTrue(result.operation.contains("5.0"))
            assertTrue(result.operation.contains("15.0"))
        }
    }
    
    @Test
    fun testCalculatorSubtraction() = runBlocking {
        TestHelper.testServer {
            val input = CalculatorRequest(10.0, 3.0, "-")
            val result = Server.typedApi.calculator.test(null, input)

            assertEquals(7.0, result.result, 0.001)
        }
    }

    @Test
    fun testCalculatorMultiplication() = runBlocking {
        TestHelper.testServer {
            val input = CalculatorRequest(4.0, 5.0, "*")
            val result = Server.typedApi.calculator.test(null, input)

            assertEquals(20.0, result.result, 0.001)
        }
    }

    @Test
    fun testCalculatorDivision() = runBlocking {
        TestHelper.testServer {
            val input = CalculatorRequest(10.0, 2.0, "/")
            val result = Server.typedApi.calculator.test(null, input)

            assertEquals(5.0, result.result, 0.001)
        }
    }

    @Test
    fun testCalculatorDivisionByZero() = runBlocking {
        TestHelper.testServer {
            val input = CalculatorRequest(10.0, 0.0, "/")

            try {
                Server.typedApi.calculator.test(null, input)
                error("Expected exception for division by zero")
            } catch (e: Exception) {
                // Expected
            }
        }
    }

    @Test
    fun testCalculatorInvalidOperation() = runBlocking {
        TestHelper.testServer {
            val input = CalculatorRequest(10.0, 5.0, "%")

            try {
                Server.typedApi.calculator.test(null, input)
                error("Expected exception for invalid operation")
            } catch (e: Exception) {
                // Expected
            }
        }
    }

    @Test
    fun testValidateEmailValid() = runBlocking {
        TestHelper.testServer {
            val input = EmailValidationRequest("user@example.com")
            val result = Server.typedApi.validateEmail.test(null, input)

            assertTrue(result.isValid)
            assertEquals("Email is valid", result.message)
            assertEquals("example.com", result.domain)
        }
    }

    @Test
    fun testValidateEmailInvalid() = runBlocking {
        TestHelper.testServer {
            val input = EmailValidationRequest("invalid-email")
            val result = Server.typedApi.validateEmail.test(null, input)

            assertFalse(result.isValid)
            assertEquals("Email format is invalid", result.message)
        }
    }

    @Test
    fun testValidateEmailComplexValid() = runBlocking {
        TestHelper.testServer {
            val input = EmailValidationRequest("user.name+tag@sub.example.com")
            val result = Server.typedApi.validateEmail.test(null, input)

            assertTrue(result.isValid)
        }
    }

    @Test
    fun testSearchBasic() = runBlocking {
        TestHelper.testServer {
            val input = SearchRequest(query = "lightning server")
            val result = Server.typedApi.search.test(null, input)

            assertNotNull(result)
            assertEquals("lightning server", result.query)
            assertTrue(result.results.isNotEmpty())
        }
    }

    @Test
    fun testSearchWithTags() = runBlocking {
        TestHelper.testServer {
            val input = SearchRequest(
                query = "lightning",
                tags = listOf("kotlin", "backend")
            )
            val result = Server.typedApi.search.test(null, input)

            assertEquals(listOf("kotlin", "backend"), result.appliedTags)
        }
    }

    @Test
    fun testSearchWithMaxResults() = runBlocking {
        TestHelper.testServer {
            val input = SearchRequest(
                query = "lightning",
                maxResults = 2
            )
            val result = Server.typedApi.search.test(null, input)

            assertTrue(result.results.size <= 2)
        }
    }

    @Test
    fun testSearchNoMatches() = runBlocking {
        TestHelper.testServer {
            val input = SearchRequest(query = "nonexistent-xyz-123")
            val result = Server.typedApi.search.test(null, input)

            assertTrue(result.results.isEmpty())
            assertEquals(0, result.totalCount)
        }
    }

    @Test
    fun testTransformUppercase() = runBlocking {
        TestHelper.testServer {
            val input = TransformRequest("hello world", TransformType.UPPERCASE)
            val result = Server.typedApi.transform.test(null, input)

            assertEquals("HELLO WORLD", result.result)
        }
    }

    @Test
    fun testTransformLowercase() = runBlocking {
        TestHelper.testServer {
            val input = TransformRequest("HELLO WORLD", TransformType.LOWERCASE)
            val result = Server.typedApi.transform.test(null, input)

            assertEquals("hello world", result.result)
        }
    }

    @Test
    fun testTransformReverse() = runBlocking {
        TestHelper.testServer {
            val input = TransformRequest("hello", TransformType.REVERSE)
            val result = Server.typedApi.transform.test(null, input)

            assertEquals("olleh", result.result)
        }
    }

    @Test
    fun testTransformCapitalize() = runBlocking {
        TestHelper.testServer {
            val input = TransformRequest("hello world", TransformType.CAPITALIZE)
            val result = Server.typedApi.transform.test(null, input)

            assertEquals("Hello World", result.result)
        }
    }

    @Test
    fun testTransformNullInput() = runBlocking {
        TestHelper.testServer {
            val input = TransformRequest(null, TransformType.UPPERCASE)
            val result = Server.typedApi.transform.test(null, input)

            assertEquals(null, result.result)
        }
    }
}
