package com.lightningkite.lightningserver.typed.validation

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.runtime.location
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.serialization.validators
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.services.data.*
import com.lightningkite.services.database.validation.AnnotationValidators
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlin.test.*

inline fun assertBadRequest(action: () -> Unit) {
    try {
        action()
        fail("BadRequestException was not thrown")
    } catch (_: BadRequestException) {
    }
}

@Serializable
data class TestModel(
    @MaxLength(5) val string: String = "12345",
    @MaxSize(3) @MaxLength(5) val list: List<String> = emptyList(),
)

class AnnotationValidatorsTests {

    object TestServer : ServerBuilder() {
        override val annotationValidators: Runtime<AnnotationValidators> = Runtime {
            AnnotationValidators(SerializersModule { })
        }

        val endpoint = path.post bind ApiHttpHandler(
            summary = "Test Endpoint",
            auth = noAuth
        ) { model: TestModel ->
            println("Got model: $model")
        }

        init {
            registerBasicMediaTypeCoders()
        }
    }

    @Test
    fun testValidators() {
        val validators = AnnotationValidators(SerializersModule { })

        suspend fun assertPasses(model: TestModel) {
            val issues = validators.validate(TestModel.serializer(), model)
            assertEquals(0, issues.size, "Did not pass, issues: $issues")
        }

        suspend fun assertFails(model: TestModel, failures: Int = 1) {
            val issues = validators.validate(TestModel.serializer(), model)
            assertEquals(failures, issues.size, "Did not fail as expected ($failures): $issues")
        }

        println(validators.prettyPrint(qualified = true))

        println(
            TestModel.serializer().let {
                for (i in 0..<it.descriptor.elementsCount) {
                    val annotations = it.descriptor.getElementAnnotations(i)
                    println("Annotations for ${it.descriptor.getElementName(i)}: $annotations")
                    println(annotations.map { it::class.toString() })
                }
            }
        )

        runBlocking {
            assertPasses(TestModel())
            assertPasses(TestModel(list = listOf("12345")))
            assertPasses(TestModel(list = List(3) { "" }))

            assertFails(TestModel(string = "123456"))
            assertFails(TestModel(list = List(4) { "" }))
            assertFails(TestModel(list = List(3) { "123456" }), 3)
        }
    }

    @Test
    fun testApiEndpointValidation() {
        TestServer.test({}) {
            println(serverRuntime.validators.prettyPrint(qualified = true))

            runBlocking {
                suspend fun handle(model: TestModel) {
                    endpoint.handle(
                        HttpRequest(
                            RawHttpEndpoint(endpoint.location.path, method = endpoint.location.method),
                            queryParameters = QueryParameters.EMPTY,
                            headers = HttpHeaders(),
                            domain = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
                            protocol = generalSettings().publicUrl.substringBefore("://"),
                            sourceIp = "localhost",
                            requestId = generateRequestId(),
                            body = TypedData.text(
                                serverRuntime.externalSerialization.json.encodeToString(
                                    endpoint.inputType,
                                    model
                                ), MediaType.Application.Json
                            ),
                        )
                    )
                }

                suspend fun assertOkay(model: TestModel) = handle(model)
                suspend fun assertFails(model: TestModel) = assertBadRequest { handle(model) }

                assertOkay(TestModel())
                assertOkay(TestModel(list = listOf("12345")))
                assertOkay(TestModel(list = List(3) { "" }))

                assertFails(TestModel(string = "123456"))
                assertFails(TestModel(list = List(4) { "" }))
                assertFails(TestModel(list = List(3) { "123456" }))
            }
        }
    }
}