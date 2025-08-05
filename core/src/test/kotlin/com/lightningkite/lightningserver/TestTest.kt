package com.lightningkite.lightningserver

import kotlin.test.Test
import kotlin.test.assertEquals

class TestTest {

    object D1: ServerDefinition() {
        override val externalSerialization: Serialization = Serialization()
        override val internalSerialization: Serialization = Serialization()

        val testEndpoint = path.resolve("test").get bind httpHandler {
            HttpResponse.plainText("Hello world!")
        }
        val testEndpointWithArg = path.resolve("test").arg<String>("arg1").get bind httpHandler {
            HttpResponse.plainText("Hello world!")
        }
    }

    val test = TestRunner(D1, settings = {
        generalServerSettings set GeneralServerSettings()
    })

    @Test fun test() {
        with(D1) {
            with(test) {
                runSuspending {
                    val response = testEndpoint.test()
                    assertEquals(HttpStatus.OK, response.status)
                    assertEquals("Hello world!", response.body!!.text())
                }
            }
        }
        D1.test(
            settings = {
                generalServerSettings set GeneralServerSettings()
            }
        ) {
            runSuspending {
                val response = testEndpoint.test()
                assertEquals(HttpStatus.OK, response.status)
                assertEquals("Hello world!", response.body!!.text())
            }
        }
    }
}