package com.lightningkite.lightningserver.engine.awsserverless

import com.amazonaws.services.lambda.runtime.ClientContext
import com.amazonaws.services.lambda.runtime.CognitoIdentity
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.LambdaLogger
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.builder.bind
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.services.data.TypedData
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AwsAdapterHttpIntegrationTest {

    object SampleServer : ServerBuilder() {
        val hello = path.path("hello").get bind com.lightningkite.lightningserver.http.HttpHandler {
            HttpResponse.plainText("hi")
        }
        val echo = path.path("echo").post bind com.lightningkite.lightningserver.http.HttpHandler { req ->
            val text = req.body?.text() ?: ""
            HttpResponse.plainText("E:$text")
        }
    }

    // Helper to set environment variables for the current JVM (best-effort; works for typical JDKs)
    private fun setEnv(key: String, value: String) {
        try {
            System.getenv()
            val env = System.getenv()
            val cl = env.javaClass
            val field = cl.getDeclaredField("m")
            field.isAccessible = true
            val map = field.get(env) as MutableMap<String, String>
            map[key] = value
        } catch (_: Throwable) {
            // No-op if we can't set; tests may fail early if AWS_REGION missing
        }
    }

    private class TestLambdaContext : Context {
        override fun getAwsRequestId(): String = "req-1"
        override fun getLogGroupName(): String = "log-group"
        override fun getLogStreamName(): String =    "log-stream"
        override fun getFunctionName(): String = "function"
        override fun getFunctionVersion(): String = "1"
        override fun getInvokedFunctionArn(): String = "arn:aws:lambda:region:acct:function:function"
        override fun getIdentity(): CognitoIdentity? = null
        override fun getClientContext(): ClientContext? = null
        override fun getRemainingTimeInMillis(): Int = 60_000
        override fun getMemoryLimitInMB(): Int = 256
        override fun getLogger(): LambdaLogger = object : LambdaLogger {
            override fun log(message: String?) { /* ignore */ }
            @Deprecated("Deprecated in AWS SDK recent versions")
            override fun log(message: ByteArray?) { /* ignore */ }
        }
    }

    private fun makeHttpEvent(
        method: String,
        stage: String,
        pathNoStage: String,
        body: String? = null,
        isBase64: Boolean = false,
        headers: Map<String, List<String>> = emptyMap(),
        query: Map<String, List<String>>? = null,
    ): APIGatewayV2HTTPEvent {
        val fullPath = "/$stage$pathNoStage"
        return APIGatewayV2HTTPEvent(
            version = "2.0",
            requestContext = APIGatewayV2HTTPEvent.RequestContext(
                accountId = "123456789012",
                apiId = "apiid",
                domainName = "example.com",
                domainPrefix = "example",
                extendedRequestId = "extid",
                httpMethod = method,
                identity = APIGatewayV2HTTPEvent.RequestContext.Identity(
                    sourceIp = "1.2.3.4",
                ),
                path = fullPath,
                protocol = "HTTP/1.1",
                requestId = "reqid",
                requestTime = "01/Jan/1970:00:00:00 +0000",
                requestTimeEpoch = 0,
                resourceId = "res",
                resourcePath = fullPath,
                stage = stage,
            ),
            resource = fullPath,
            body = body,
            multiValueHeaders = headers,
            httpMethod = method,
            isBase64Encoded = isBase64,
            path = fullPath,
            multiValueQueryStringParameters = query,
        )
    }

    @Test
    fun http_get_routed_through_adapter() {
        setEnv("AWS_REGION", "us-east-1")
        val adapter = AwsAdapter(SampleServer.build())
        val event = makeHttpEvent(method = "GET", stage = "prod", pathNoStage = "/hello")
        val json = adapter.internalSerialization.json.encodeToString(event)
        val input = json.byteInputStream()
        val output = java.io.ByteArrayOutputStream()
        adapter.handleRequest(input, output, TestLambdaContext())
        val responseJson = output.toByteArray().toString(Charsets.UTF_8)
        val response = adapter.internalSerialization.json.decodeFromString(APIGatewayV2HTTPResponse.serializer(), responseJson)
        assertEquals(200, response.statusCode)
        assertEquals("hi", response.body)
        assertNotNull(response.headers["content-type"]) // text/plain should be present (lowercased key)
    }

    @Test
    fun http_post_with_text_body_round_trips() {
        setEnv("AWS_REGION", "us-east-1")
        val adapter = AwsAdapter(SampleServer.build())
        val bodyText = "abc"
        val event = makeHttpEvent(
            method = "POST",
            stage = "prod",
            pathNoStage = "/echo",
            body = bodyText,
            isBase64 = false,
            headers = mapOf("Content-Type" to listOf("text/plain"))
        )
        val json = adapter.internalSerialization.json.encodeToString(event)
        val input = json.byteInputStream()
        val output = java.io.ByteArrayOutputStream()
        adapter.handleRequest(input, output, TestLambdaContext())
        val responseJson = output.toByteArray().toString(Charsets.UTF_8)
        val response = adapter.internalSerialization.json.decodeFromString(APIGatewayV2HTTPResponse.serializer(), responseJson)
        assertEquals(200, response.statusCode)
        assertEquals("E:$bodyText", response.body)
    }

    @Test
    fun http_unknown_path_returns_404() {
        setEnv("AWS_REGION", "us-east-1")
        val adapter = AwsAdapter(SampleServer.build())
        val event = makeHttpEvent(method = "GET", stage = "prod", pathNoStage = "/missing")
        val json = adapter.internalSerialization.json.encodeToString(event)
        val input = json.byteInputStream()
        val output = java.io.ByteArrayOutputStream()
        adapter.handleRequest(input, output, TestLambdaContext())
        val responseJson = output.toByteArray().toString(Charsets.UTF_8)
        val response = adapter.internalSerialization.json.decodeFromString(APIGatewayV2HTTPResponse.serializer(), responseJson)
        assertEquals(404, response.statusCode)
    }
}
