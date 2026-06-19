// This file was automatically generated from first-endpoint.md by Knit tool. Do not edit.
package com.lightningkite.lightningserver.guide.exampleFirstEndpoint02

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.arg1
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.test.test
import kotlinx.coroutines.runBlocking

object GreetServer : ServerBuilder() {

    // GET /greet/{name}
    val greet = path.path("greet").arg<String>("name").get bind HttpHandler { request ->
        val name = request.path.arg1
        HttpResponse.plainText("Hello, $name!")
    }
}

fun main() = runBlocking {
    GreetServer.test({}) {
        val response = GreetServer.greet.test("World")
        println(response.body?.text())
    }
}
