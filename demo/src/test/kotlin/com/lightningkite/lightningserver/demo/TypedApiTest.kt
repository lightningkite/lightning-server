package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.demo.endpoints.*
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.sdk.Archive
import com.lightningkite.lightningserver.typed.sdk.TypescriptFetcherSdk
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.data.ExperimentalLightningServer
import com.lightningkite.services.database.Database
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlinx.io.readString
import kotlinx.serialization.Serializable
import org.junit.Test
import kotlin.test.*
import kotlin.uuid.Uuid

class TypedApiTest {


    object TsQuickCheckServer : ServerBuilder() {
        @JvmInline @Serializable value class IDThing(val uuid: Uuid)
        val uuidTest = path.path("test").post bind ApiHttpHandler(
            summary = "uuid raw test",
            auth = noAuth,
            implementation = { id: IDThing ->
                id
            }
        )
    }
    @OptIn(ExperimentalLightningServer::class)
    @Test
    fun tsSdkQuickCheck() = runBlocking {
        TsQuickCheckServer.test(settings = {}) {
            TypescriptFetcherSdk(
                fileStructure = TypescriptFetcherSdk.Structure.SingleFile("sdk.kt"),
            ).write(object: Archive {
                override fun sub(name: String): Archive {
                    println("SUB $name")
                    return this
                }

                override fun entry(name: String, write: (Sink) -> Unit) {
                    println("--$name--")
                    System.out.asSink().buffered().use {
                        write(it)
                    }
                    println("----")
                }

                override fun close() {
                    println("CLOSE")
                }

            })
        }
        Unit
    }

    object TsSealedServer : ServerBuilder() {
        @Serializable
        sealed interface Shape {
            @Serializable data class Circle(val radius: Double) : Shape
            @Serializable data class Rectangle(val width: Double, val height: Double) : Shape
        }
        val shapeTest = path.path("shape").post bind ApiHttpHandler(
            summary = "sealed result test",
            auth = noAuth,
            implementation = { input: Shape -> input }
        )
    }

    /**
     * App `@Serializable sealed` types must emit as a flat-discriminator TS union plus an interface
     * per subtype, matching the runtime wire format `{ "type": "<name>", ...subtype fields }`.
     */
    @OptIn(ExperimentalLightningServer::class)
    @Test
    fun tsSdkSealedTypes() = runBlocking {
        val files = HashMap<String, String>()
        TsSealedServer.test(settings = {}) {
            TypescriptFetcherSdk(
                fileStructure = TypescriptFetcherSdk.Structure.SingleFile("sdk.ts"),
            ).write(object : Archive {
                override fun sub(name: String): Archive = this
                override fun entry(name: String, write: (Sink) -> Unit) {
                    val buffer = Buffer()
                    write(buffer)
                    files[name] = buffer.readString()
                }
                override fun close() {}
            })
        }
        val ts = files.values.joinToString("\n")
        println(ts)

        // The sealed union type itself.
        assertTrue(ts.contains("export type ") && Regex("""export type \w*Shape =""").containsMatchIn(ts),
            "sealed union type not emitted:\n$ts")
        // Flat-discriminator union members referencing the per-subtype interfaces.
        assertTrue(Regex("""\| \(\{ type: "[^"]*Circle" \} & \w*ShapeCircle\)""").containsMatchIn(ts),
            "Circle union member not emitted in discriminator form:\n$ts")
        assertTrue(Regex("""\| \(\{ type: "[^"]*Rectangle" \} & \w*ShapeRectangle\)""").containsMatchIn(ts),
            "Rectangle union member not emitted in discriminator form:\n$ts")
        // The subtype interfaces, with their fields.
        assertTrue(Regex("""interface \w*ShapeCircle \{[^}]*radius: number""").containsMatchIn(ts),
            "Circle subtype interface/fields not emitted:\n$ts")
        assertTrue(Regex("""interface \w*ShapeRectangle \{[^}]*width: number""").containsMatchIn(ts),
            "Rectangle subtype interface/fields not emitted:\n$ts")
        Unit
    }

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
