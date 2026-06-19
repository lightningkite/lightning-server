package com.lightningkite.lightningserver.guide.samples

import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.arg1
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

// region hello-server
object HelloServer : ServerBuilder() {

    // GET / — responds with a plain-text greeting
    val root = path.get bind HttpHandler {
        HttpResponse.plainText("Hello, Lightning Server!")
    }
}
// endregion hello-server

// region greet-server
object GreetServer : ServerBuilder() {

    // GET /greet/{name}
    val greet = path.path("greet").arg<String>("name").get bind HttpHandler { request ->
        val name = request.path.arg1
        HttpResponse.plainText("Hello, $name!")
    }
}
// endregion greet-server

// region echo-types
@Serializable
data class EchoRequest(val message: String)

@Serializable
data class EchoResponse(val echo: String, val length: Int)
// endregion echo-types

// region echo-server
object EchoServer : ServerBuilder() {

    // POST /echo — accepts typed JSON, returns typed JSON
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
// endregion echo-server

// Top-level function referenced by @sample in ApiHttpHandler KDoc.
// Shows the idiomatic way to define and test a typed, unauthenticated endpoint.
fun echoServerSample() = runBlocking {
    EchoServer.test(settings = {}) {
        // ApiHttpHandler.test() accepts null auth for noAuth endpoints and
        // returns the typed output directly — no JSON manipulation needed.
        val result = EchoServer.echo.test(null, EchoRequest("ping"))
        check(result.echo == "ping")
        check(result.length == 4)
    }
}
