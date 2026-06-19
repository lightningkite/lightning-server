// This file was automatically generated from first-endpoint.md by Knit tool. Do not edit.
package com.lightningkite.lightningserver.guide.exampleFirstEndpoint03

import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.runtime.test.test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData

@Serializable
data class EchoRequest(val message: String)

@Serializable
data class EchoResponse(val echo: String, val length: Int)

object EchoServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    // POST /echo — accepts JSON, returns JSON
    val echo = path.path("echo").post bind ApiHttpHandler(
        summary = "Echo a message",
        description = "Returns the message back with its character count.",
        auth = noAuth,
        implementation = { input: EchoRequest ->
            EchoResponse(
                echo = input.message,
                length = input.message.length
            )
        }
    )
}

fun main() = runBlocking {
    EchoServer.test({}) {
        // Send a JSON body and get back the JSON response through the full HTTP pipeline
        val response = EchoServer.echo.test(
            body = TypedData.text("""{"message":"ping"}""", MediaType.Application.Json)
        )
        // The response body is JSON; print just the echo field from it
        val body = response.body?.text() ?: ""
        println(body.contains("\"echo\":\"ping\""))
        println(body.contains("\"length\":4"))
    }
}
