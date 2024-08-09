package com.lightningkite.lightningserver.typed

import com.lightningkite.UUID
import com.lightningkite.lightningdb.contextualSerializerIfHandled
import com.lightningkite.lightningdb.test.ClassUsedForEmbedding
import com.lightningkite.lightningdb.test.LargeTestModel
import com.lightningkite.lightningdb.test.SimpleLargeTestModel
import com.lightningkite.lightningdb.test.ValidatedModel
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.encodeToByteArray
import com.lightningkite.now
import com.lightningkite.uuid
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ApiEndpointValidateTest {
    @Test
    fun test() {
        runBlocking {
            TestSettings.sample4(
                HttpRequest(
                    endpoint = TestSettings.sample4.route.endpoint,
                    parts = mapOf(),
                    body = HttpContent.json(ValidatedModel("Sample Name"))
                )
            ).also { println(it) }
            assertFailsWith<BadRequestException> {
                TestSettings.sample4(
                    HttpRequest(
                        endpoint = TestSettings.sample4.route.endpoint,
                        parts = mapOf(),
                        body = HttpContent.json(ValidatedModel("Invalid!"))
                    )
                ).also { println(it) }
            }
            assertFailsWith<BadRequestException> {
                TestSettings.sample4(
                    HttpRequest(
                        endpoint = TestSettings.sample4.route.endpoint,
                        parts = mapOf(),
                        body = HttpContent.json(ValidatedModel("Way toooooooooooooo looooooong"))
                    )
                ).also { println(it) }
            }
        }
    }

    @Test
    fun testPrimitiveFormats() {
        runBlocking {
            TestSettings
            for (formatType in Serialization.parsers.keys.toSet().intersect(Serialization.emitters.keys.toSet())) {
                println("Checking $formatType")
                val parser = Serialization.parsers[formatType]!!
                val emitter = Serialization.emitters[formatType]!!
                TestSettings.sample5(
                    HttpRequest(
                        endpoint = TestSettings.sample5.route.endpoint,
                        parts = mapOf(),
                        headers = HttpHeaders { set(HttpHeader.Accept, formatType.toString()) },
                        body = emitter(formatType, Serialization.module.contextualSerializerIfHandled<UUID>(), uuid())
                    )
                ).also {
                    println(it)
                    parser(it.body!!, Serialization.module.contextualSerializerIfHandled<UUID>())
                }
            }
        }
    }

    @Test
    fun testFormats() {
        runBlocking {
            TestSettings
            for (formatType in Serialization.parsers.keys.toSet().intersect(Serialization.emitters.keys.toSet())) {
                println("Checking $formatType")
                val parser = Serialization.parsers[formatType]!!
                val emitter = Serialization.emitters[formatType]!!
                TestSettings.sample6(
                    HttpRequest(
                        endpoint = TestSettings.sample6.route.endpoint,
                        parts = mapOf(),
                        headers = HttpHeaders { set(HttpHeader.Accept, formatType.toString()) },
                        body = emitter(formatType, Serialization.module.contextualSerializerIfHandled<SimpleLargeTestModel>(), SimpleLargeTestModel(
                            boolean = true,
                            byte = 100,
                            short = 1000,
                            int = 10000,
                            long = 100000,
                            float = 0.5f,
                            double = 0.75,
                            char = 'A',
                            string = "Test",
                            uuid = uuid(),
                            instant = now(),
                            listEmbedded = listOf(
                                ClassUsedForEmbedding("first", 1),
                                ClassUsedForEmbedding("second", 2),
                            ),
//                            map = mapOf("first" to 1, "second" to 2),
                        ))
                    )
                ).also {
                    println(it)
                    parser(it.body!!, Serialization.module.contextualSerializerIfHandled<SimpleLargeTestModel>())
                }
            }
        }
    }
}