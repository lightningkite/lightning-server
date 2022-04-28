package com.lightningkite.ktorbatteries.serialization

import io.ktor.http.cio.*
import io.ktor.utils.io.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class MultipartAltKtTest {
    @Test fun test() {
        val multipart = """
-----------------------------169151118641476640972656201151
Content-Disposition: form-data; name="__json"

{"_id":"ce00b795-8963-40a0-b3a5-0def03bd9f2c","timestamp":"2022-04-28T05:38:04.806Z","name":"No Name3","number":3123,"content":""}
-----------------------------169151118641476640972656201151
Content-Disposition: form-data; name="TestModel[_id]"

ce00b795-8963-40a0-b3a5-0def03bd9f2c
-----------------------------169151118641476640972656201151
Content-Disposition: form-data; name="TestModel[timestamp]"

2022-04-28T05:38:04.000Z
-----------------------------169151118641476640972656201151
Content-Disposition: form-data; name="TestModel[name]"

No Name3
-----------------------------169151118641476640972656201151
Content-Disposition: form-data; name="TestModel[number]"

3123
-----------------------------169151118641476640972656201151
Content-Disposition: form-data; name="TestModel[content]"


-----------------------------169151118641476640972656201151--
        """.trimIndent().replace("\n", "\r\n")
        runBlocking {
            parseMultipart(ByteReadChannel(multipart.toByteArray())).consumeEach {
                when(it) {
                    is MultipartEvent.Preamble -> println("Got preamble ${it.body.readText()}")
                    is MultipartEvent.MultipartPart -> println("Got part ${it.body.readRemaining().readText()} with headers ${it.headers.await()}")
                    is MultipartEvent.Epilogue -> println("Got Epilogue ${it.body.readText()}")
                }
            }
            parseMultipart(ByteReadChannel(multipart.toByteArray()), "multipart/form-data; boundary=---------------------------169151118641476640972656201151", null).consumeEach {
                when(it) {
                    is MultipartEvent.Preamble -> println("Got preamble ${it.body.readText()}")
                    is MultipartEvent.MultipartPart -> println("Got part ${it.body.readRemaining().readText()} with headers ${it.headers.await()}")
                    is MultipartEvent.Epilogue -> println("Got Epilogue ${it.body.readText()}")
                }
            }

            @Suppress("DEPRECATION_ERROR")
            parseMultipart(ByteBuffer.wrap("\r\n-----------------------------169151118641476640972656201151".toByteArray()), ByteReadChannel(multipart.toByteArray()), null).consumeEach {
                when(it) {
                    is MultipartEvent.Preamble -> println("Got preamble ${it.body.readText()}")
                    is MultipartEvent.MultipartPart -> println("Got part ${it.body.readRemaining().readText()} with headers ${it.headers.await()}")
                    is MultipartEvent.Epilogue -> println("Got Epilogue ${it.body.readText()}")
                }
            }
        }
    }
}