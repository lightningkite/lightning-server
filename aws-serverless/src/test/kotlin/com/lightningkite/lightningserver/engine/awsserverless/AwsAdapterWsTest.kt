package com.lightningkite.lightningserver.engine.awsserverless

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test

class AwsAdapterWsTest {
    object SampleServer : ServerBuilder() {
        val hello = path.path("hello").get bind com.lightningkite.lightningserver.http.HttpHandler {
            HttpResponse.plainText("hi")
        }
        val echo = path.path("echo") bind WebSocketHandler(
            storageSerializer = Unit.serializer(),
            willConnect = { println("willConnect");  Unit },
            didConnect = { println("didConnect");  },
            topicHandlers = {  },
            messageFromClient = { println("send"); send(it) },
            disconnect = { println("disconnect"); }
        )
        init { registerBasicMediaTypeCoders() }
    }

    @Test
    fun basicStartup() {
        val adapter = TestAwsAdapter(SampleServer.build())
        adapter.beforeCheckpoint(null)
    }


    @Test fun fullSocket() {
        val adapter = TestAwsAdapter(SampleServer.build())
        val connectionId = "test"
        val channel = adapter.websocketChannel(connectionId)
        GlobalScope.launch {
            while(true) {
                println("Sent " + channel.receive())
            }
        }
        val baseMessage = APIGatewayV2WebsocketRequest(
            multiValueHeaders = mapOf(),
            multiValueQueryStringParameters = mapOf(),
            requestContext = APIGatewayV2WebsocketRequest.RequestContext(
                routeKey = "",
                eventType = "",
                extendedRequestId = "",
                requestTime = "",
                messageDirection = "",
                stage = "",
                connectedAt = 0L,
                requestTimeEpoch = 0L,
                identity = APIGatewayV2WebsocketRequest.RequestContext.Identity("", ""),
                requestId = "",
                domainName = "",
                connectionId = connectionId,
                apiId = "",
            ),
            isBase64Encoded = false,
            body = ""
        )
        adapter.handleRequest(baseMessage.copy(
            multiValueQueryStringParameters = mapOf(
                "path" to listOf("/echo")
            ),
            requestContext = baseMessage.requestContext.copy(
                routeKey = "\$connect"
            )
        ))
        adapter.handleRequest(baseMessage.copy(
            requestContext = baseMessage.requestContext.copy(
                routeKey = "blah"
            ),
            body = "Ping!"
        ))
        adapter.handleRequest(baseMessage.copy(
            requestContext = baseMessage.requestContext.copy(
                routeKey = "\$disconnect"
            )
        ))
    }
}