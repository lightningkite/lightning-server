// This file was automatically generated from first-endpoint.md by Knit tool. Do not edit.
package com.lightningkite.lightningserver.guide.exampleFirstEndpoint01

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.test.test
import kotlinx.coroutines.runBlocking

object HelloServer : ServerBuilder() {

    // GET / — responds with a plain-text greeting
    val root = path.get bind HttpHandler {
        HttpResponse.plainText("Hello, Lightning Server!")
    }
}

fun main() = runBlocking {
    HelloServer.test({}) {
        val response = HelloServer.root.test()
        println(response.body?.text())
    }
}
