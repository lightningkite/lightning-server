package com.lightningkite.lightningserver.demo.endpoints

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.arg1
import com.lightningkite.lightningserver.pathing.arg2
import com.lightningkite.lightningserver.plainText
import com.lightningkite.services.data.TypedData
import kotlinx.serialization.json.*

/**
 * BasicExamplesEndpoints - Demonstrates fundamental HTTP endpoint patterns.
 * 
 * These examples show:
 * - Simple GET requests
 * - Path parameters with type safety
 * - Query parameters
 * - POST with request body
 * - Different response types (plain text, JSON, HTML)
 */
object BasicExamplesEndpoints : ServerBuilder() {
    
    /**
     * GET /
     * 
     * The simplest possible endpoint - returns a plain text greeting.
     * This is the root endpoint that demonstrates basic HTTP GET handling.
     */
    val root = path.get bind HttpHandler {
        HttpResponse.plainText("Welcome to Lightning Server Demo!")
    }
    
    /**
     * GET /hello/{name}
     * 
     * Demonstrates path parameters with type safety.
     * The {name} parameter is extracted as a String and made available via request.path.arg1.
     * 
     * Example: GET /hello/Alice -> "Hello, Alice!"
     */
    val helloWithName = path.path("hello").arg<String>("name").get bind HttpHandler { request ->
        val name = request.path.arg1
        HttpResponse.plainText("Hello, $name!")
    }
    
    /**
     * GET /greet?name=...&title=...
     * 
     * Demonstrates query parameters.
     * Shows how to extract optional query parameters with defaults.
     * 
     * Example: GET /greet?name=Bob&title=Dr -> "Hello, Dr Bob!"
     */
    val greetWithQuery = path.path("greet").get bind HttpHandler { request ->
        val name = request.queryParameters["name"] ?: "Guest"
        val title = request.queryParameters["title"]
        val greeting = if (title != null) {
            "Hello, $title $name!"
        } else {
            "Hello, $name!"
        }
        HttpResponse.plainText(greeting)
    }
    
    /**
     * POST /echo
     * 
     * Demonstrates handling POST requests with a body.
     * Echoes back the request body as plain text.
     * 
     * Example: POST /echo with body "test message" -> "You sent: test message"
     */
    val echo = path.path("echo").post bind HttpHandler { request ->
        val body = request.body?.text() ?: "(empty)"
        HttpResponse.plainText("You sent: $body")
    }
    
    /**
     * GET /calc/{a}/{b}
     * 
     * Demonstrates multiple path parameters with different types.
     * Shows type conversion and basic arithmetic.
     * 
     * Example: GET /calc/5/3 -> "5 + 3 = 8"
     */
    val calculate = path.path("calc").arg<Int>("a").arg<Int>("b").get bind HttpHandler { request ->
        val a = request.path.arg1
        val b = request.path.arg2
        val result = """
            |Calculator Results:
            |$a + $b = ${a + b}
            |$a - $b = ${a - b}
            |$a × $b = ${a * b}
            |$a ÷ $b = ${if (b != 0) a / b else "undefined"}
        """.trimMargin()
        HttpResponse.plainText(result)
    }
    
    /**
     * GET /info
     *
     * Demonstrates returning JSON responses.
     * Shows how to return structured data as JSON.
     */
    val info = path.path("info").get bind HttpHandler {
        val jsonObject = buildJsonObject {
            put("name", "Lightning Server Demo")
            put("version", "5.0")
            put("description", "A comprehensive demonstration of Lightning Server capabilities")
            putJsonArray("features") {
                add("Type-safe endpoints")
                add("Auto-generated documentation")
                add("Multi-platform SDK generation")
                add("Database abstractions")
                add("Authentication & authorization")
                add("File handling")
                add("WebSockets")
                add("Background tasks")
            }
        }
        val jsonString = jsonObject.toString()
        HttpResponse(
            body = TypedData.text(jsonString, MediaType.Application.Json)
        )
    }
    
    /**
     * GET /special-chars/{value}
     * 
     * Demonstrates proper handling of URL encoding and special characters.
     * Shows that path parameters are automatically decoded.
     * 
     * Example: GET /special-chars/hello%2Fworld -> "You sent: hello/world"
     */
    val specialChars = path.path("special-chars").arg<String>("value").get bind HttpHandler { request ->
        val value = request.path.arg1
        HttpResponse.plainText("You sent: $value")
    }
}
