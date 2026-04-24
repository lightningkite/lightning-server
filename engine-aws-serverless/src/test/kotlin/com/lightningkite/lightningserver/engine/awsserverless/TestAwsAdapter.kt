package com.lightningkite.lightningserver.engine.awsserverless

import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.services.cache.dynamodb.embeddedDynamo
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.http.SdkHttpResponse
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.apigatewaymanagementapi.model.*
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.lambda.model.*
import java.io.ByteArrayOutputStream

class TestAwsAdapter(server: ServerDefinition) : AwsAdapter(server) {
    override val dynamo: DynamoDbAsyncClient
        get() = embeddedDynamo()

    override val region: Region
        get() = Region.US_WEST_2

    override fun loadSettings() {
        with(settings) {
            settings.forEach {
                println("Using default for ${it.name}")
                it.useDefault()
            }
            ready()
        }
    }

    val websocketChannels = HashMap<String, Channel<String>>()
    fun websocketChannel(id: String): Channel<String> = websocketChannels.getOrPut(id) { Channel() }
    override suspend fun apiGatewayWsDeleteConnection(request: DeleteConnectionRequest): DeleteConnectionResponse {
        println("delete ${request.connectionId()}")
        request.connectionId().let { websocketChannels.remove(it)?.close() }
        return DeleteConnectionResponse.builder().apply {
            sdkHttpResponse(
                SdkHttpResponse.builder().statusCode(200).build()
            )
        }.build()
    }

    override suspend fun apiGatewayWsPostToConnection(request: PostToConnectionRequest): PostToConnectionResponse {
        println("post ${request.connectionId()} ${request.data().asUtf8String()}")
        websocketChannels.getOrPut(request.connectionId()) { Channel() }.send(request.data().asUtf8String())
        return PostToConnectionResponse.builder().apply {
            sdkHttpResponse(
                SdkHttpResponse.builder().statusCode(200).build()
            )
        }.build()
    }

    @OptIn(DelicateCoroutinesApi::class)
    override suspend fun invokeLambda(invokeRequest: InvokeRequest): InvokeResponse {
        if (invokeRequest.invocationType() == InvocationType.EVENT) {
            GlobalScope.async {
                val out = ByteArrayOutputStream()
                this@TestAwsAdapter.handleRequest(
                    invokeRequest.payload().asInputStream(),
                    out,
                    TestLambdaContext()
                )
            }
            return InvokeResponse.builder().statusCode(200).build()
        } else {
            val out = ByteArrayOutputStream()
            this@TestAwsAdapter.handleRequest(
                invokeRequest.payload().asInputStream(),
                out,
                TestLambdaContext()
            )
            return InvokeResponse.builder()
                .payload(SdkBytes.fromByteArrayUnsafe(out.toByteArray()))
                .statusCode(200).build()
        }
    }

    fun handleRequest(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        handleRequest(input.inputStream(), out, TestLambdaContext())
        return out.toByteArray()
    }

    inline fun <reified T : AwsLambdaInput> handleRequest(input: T): ByteArray =
        handleRequest(Json.encodeToString<T>(input).toByteArray())
}