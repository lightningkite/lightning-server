package com.lightningkite.lightningserver.guide.samples

// region imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.typed.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
// endregion imports

// region hello-server
object HelloServer : ServerBuilder() {

    // GET / — responds with a plain-text greeting
    val root = path.get bind HttpHandler {
        HttpResponse.plainText("Hello, Lightning Server!")
    }
}
// endregion hello-server

// region hello-server-test
fun helloServerTest() = runBlocking {
    HelloServer.test(settings = {}) {
        val response = HelloServer.root.test()
        check(response.body?.text() == "Hello, Lightning Server!")
    }
}
// endregion hello-server-test

// region greet-server
object GreetServer : ServerBuilder() {

    // GET /greet/{name}
    val greet = path.path("greet").arg<String>("name").get bind HttpHandler { request ->
        val name = request.path.arg1
        HttpResponse.plainText("Hello, $name!")
    }
}
// endregion greet-server

// region greet-server-test
fun greetServerTest() = runBlocking {
    GreetServer.test(settings = {}) {
        val response = GreetServer.greet.test("World")
        check(response.body?.text() == "Hello, World!")
    }
}
// endregion greet-server-test

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

// region echo-server-test
fun echoServerTest() = runBlocking {
    EchoServer.test(settings = {}) {
        // ApiHttpHandler.test() accepts null auth for noAuth endpoints and
        // returns the typed output directly — no JSON manipulation needed.
        val result = EchoServer.echo.test(null, EchoRequest("ping"))
        check(result.echo == "ping")
        check(result.length == 4)
    }
}
// endregion echo-server-test


// Top-level function referenced by @sample in ApiHttpHandler KDoc.
fun echoServerSample() = runBlocking {
    EchoServer.test(settings = {}) {
        val result = EchoServer.echo.test(null, EchoRequest("ping"))
        check(result.echo == "ping")
        check(result.length == 4)
    }
}
