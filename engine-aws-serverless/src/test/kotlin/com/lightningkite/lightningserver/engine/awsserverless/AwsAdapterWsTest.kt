package com.lightningkite.lightningserver.engine.awsserverless

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import kotlinx.coroutines.*
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

class AwsAdapterWsTest {
    object SampleServer : ServerBuilder() {
        val hello = path.path("hello").get bind com.lightningkite.lightningserver.http.HttpHandler {
            HttpResponse.plainText("hi")
        }
        val echo = path.path("echo") bind WebSocketHandler(
            storageSerializer = Unit.serializer(),
            willConnect = { println("willConnect"); Unit },
            didConnect = { println("didConnect"); },
            topicHandlers = { },
            messageFromClient = { println("send"); send(it) },
            disconnect = { println("disconnect"); }
        )

        /**
         * Commits state twice while handling a single message, the way the multiplex handler does when it
         * registers a channel and then subscribes it.
         */
        val counter = path.path("counter") bind WebSocketHandler(
            storageSerializer = Int.serializer(),
            willConnect = { 0 },
            topicHandlers = { },
            messageFromClient = {
                updateStateImmediately { it + 1 }
                updateStateImmediately { it + 1 }
                send(WebSocketFrame(currentState.toString()))
            },
        )

        init {
            registerBasicMediaTypeCoders()
        }
    }

    @Test
    fun basicStartup() {
        val adapter = TestAwsAdapter(SampleServer.build())
        adapter.beforeCheckpoint(null)
    }


    private fun baseMessage(connectionId: String) = APIGatewayV2WebSocketRequest(
        multiValueHeaders = mapOf(),
        multiValueQueryStringParameters = mapOf(),
        requestContext = APIGatewayV2WebSocketRequest.RequestContext(
            routeKey = "",
            eventType = "",
            extendedRequestId = "",
            requestTime = "",
            messageDirection = "",
            stage = "",
            connectedAt = 0L,
            requestTimeEpoch = 0L,
            identity = APIGatewayV2WebSocketRequest.RequestContext.Identity("", ""),
            requestId = "",
            domainName = "",
            connectionId = connectionId,
            apiId = "",
        ),
        isBase64Encoded = false,
        body = ""
    )

    /**
     * A handler that commits state more than once while handling a single message must not lose the
     * optimistic lock to its own earlier commit.  Regression test: the commit loop used to compare against
     * the state the invocation started with, so every commit after the first was rejected, and the retry
     * that followed would kill the socket if its state row was not immediately readable.
     */
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun repeatedCommitsInOneInvocationKeepTheOptimisticLock() {
        val adapter = TestAwsAdapter(SampleServer.build())
        val connectionId = "counter-socket"
        val channel = adapter.webSocketChannel(connectionId)
        val sent = CompletableDeferred<String>()
        GlobalScope.launch { sent.complete(channel.receive()) }

        val baseMessage = baseMessage(connectionId)
        adapter.handleRequest(
            baseMessage.copy(
                multiValueQueryStringParameters = mapOf("path" to listOf("/counter")),
                requestContext = baseMessage.requestContext.copy(routeKey = "\$connect")
            )
        )
        // Let the asynchronous didConnect invocation settle so it cannot be confused with a self-conflict.
        adapter.awaitPendingInvocations()
        val failuresBeforeMessage = adapter.countingDynamo.conditionalCheckFailures.get()

        adapter.handleRequest(
            baseMessage.copy(
                requestContext = baseMessage.requestContext.copy(routeKey = "\$default"),
                body = "go"
            )
        )
        adapter.awaitPendingInvocations()

        assertEquals(
            "2",
            runBlocking { withTimeout(10_000) { sent.await() } },
            "Both state updates should have been applied and the socket should still be open"
        )
        assertEquals(
            failuresBeforeMessage,
            adapter.countingDynamo.conditionalCheckFailures.get(),
            "A second commit within one invocation must not lose the optimistic lock to the first"
        )
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun fullSocket() {
        val adapter = TestAwsAdapter(SampleServer.build())
        val connectionId = "test"
        val channel = adapter.webSocketChannel(connectionId)
        GlobalScope.launch {
            while (true) {
                println("Sent " + channel.receive())
            }
        }
        val baseMessage = baseMessage(connectionId)
        adapter.handleRequest(
            baseMessage.copy(
                multiValueQueryStringParameters = mapOf(
                    "path" to listOf("/echo")
                ),
                requestContext = baseMessage.requestContext.copy(
                    routeKey = "\$connect"
                )
            )
        )
        adapter.handleRequest(
            baseMessage.copy(
                requestContext = baseMessage.requestContext.copy(
                    routeKey = "blah"
                ),
                body = "Ping!"
            )
        )
        adapter.handleRequest(
            baseMessage.copy(
                requestContext = baseMessage.requestContext.copy(
                    routeKey = "\$disconnect"
                )
            )
        )
    }
}