package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.ApiWebSocketHandler
import com.lightningkite.lightningserver.typed.ClientWebSocket
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

interface GreetingClient {
    suspend fun hello(): String
    suspend fun echo(input: Int): Int
    suspend fun helloTwice(): String = hello() + hello()
    fun sum(): ClientWebSocket<Int, Int>
}

class ClientInterfaceCheckTest {
    private class Greetings(
        withEcho: Boolean,
        extra: Boolean,
        stringEcho: Boolean = false,
        withSum: Boolean = true,
        stringSum: Boolean = false,
    ) : ServerBuilder() {
        init {
            sdkSettings.clientInterface = GreetingClient::class.info()
        }

        val hello = path.path("hello").get bind ApiHttpHandler(
            summary = "Hello",
            auth = noAuth,
            implementation = { _: Unit -> "hello" }
        )

        init {
            if (withEcho) path.path("echo").post bind ApiHttpHandler(
                summary = "Echo",
                auth = noAuth,
                implementation = { input: Int -> input }
            )
            if (stringEcho) path.path("echo").post bind ApiHttpHandler(
                summary = "Echo",
                auth = noAuth,
                implementation = { input: String -> 0 }
            )
            if (withSum) path.path("sum") bind ApiWebSocketHandler<PathSpec0, Int, Nothing?, Int, Int>(
                summary = "Sum",
                auth = noAuth,
                willConnectType = { 0 },
            )
            if (stringSum) path.path("sum") bind ApiWebSocketHandler<PathSpec0, Int, Nothing?, String, Int>(
                summary = "Sum",
                auth = noAuth,
                willConnectType = { 0 },
            )
            if (extra) path.path("extra").post bind ApiHttpHandler(
                summary = "Extra",
                auth = noAuth,
                implementation = { input: Int -> input }
            )
        }
    }

    private class Root(greetings: Greetings) : ServerBuilder() {
        val greetings = path.path("greetings") module greetings
    }

    @Test
    fun matchingModuleBuilds() {
        Root(Greetings(withEcho = true, extra = false)).build()
    }

    @Test
    fun missingEndpointFailsTheBuild() {
        val error = assertFailsWith<IllegalStateException> { Root(Greetings(withEcho = false, extra = false)).build() }
        assertContains(error.message!!, "The module at /greetings")
        assertContains(error.message!!, "- echo(kotlin.Int): kotlin.Int has no endpoint")
    }

    @Test
    fun wrongArgumentTypeFailsTheBuild() {
        val error = assertFailsWith<IllegalStateException> { Root(Greetings(withEcho = false, extra = false, stringEcho = true)).build() }
        assertContains(error.message!!, "- echo(kotlin.Int): kotlin.Int doesn't match the endpoint's (kotlin.String): kotlin.Int")
    }

    @Test
    fun missingWebSocketFailsTheBuild() {
        val error = assertFailsWith<IllegalStateException> { Root(Greetings(withEcho = true, extra = false, withSum = false)).build() }
        assertContains(error.message!!, "- sum(): ClientWebSocket<kotlin.Int, kotlin.Int> has no websocket")
    }

    @Test
    fun wrongWebSocketTypeFailsTheBuild() {
        val error = assertFailsWith<IllegalStateException> {
            Root(Greetings(withEcho = true, extra = false, withSum = false, stringSum = true)).build()
        }
        assertContains(error.message!!, "- sum(): ClientWebSocket<kotlin.Int, kotlin.Int> doesn't match the websocket's (): ClientWebSocket<kotlin.String, kotlin.Int>")
    }

    @Test
    fun extraEndpointsAreAllowed() {
        Root(Greetings(withEcho = true, extra = true)).build()
    }
}
