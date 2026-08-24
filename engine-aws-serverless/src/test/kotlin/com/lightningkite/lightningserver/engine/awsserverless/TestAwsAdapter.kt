package com.lightningkite.lightningserver.engine.awsserverless

import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.services.cache.dynamodb.DynamoDbAsyncClientDelegate
import com.lightningkite.services.cache.dynamodb.embeddedDynamo
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.http.SdkHttpResponse
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.apigatewaymanagementapi.model.*
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse
import software.amazon.awssdk.services.lambda.model.*
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

/**
 * Wraps a DynamoDB client to count rejected conditional writes, letting tests assert that socket state
 * commits are not losing their own optimistic lock.
 *
 * Extends [DynamoDbAsyncClientDelegate] rather than using Kotlin interface delegation: the SDK declares
 * these methods as Java defaults that throw, and Kotlin's `by` only generates overrides for abstract members.
 */
class CountingDynamoDbAsyncClient(basis: DynamoDbAsyncClient) : DynamoDbAsyncClientDelegate(basis) {
    val conditionalCheckFailures: AtomicInteger = AtomicInteger()

    override fun updateItem(updateItemRequest: UpdateItemRequest): CompletableFuture<UpdateItemResponse> =
        super.updateItem(updateItemRequest).whenComplete { _, thrown ->
            if (generateSequence(thrown) { it.cause }.take(10).any { it is ConditionalCheckFailedException })
                conditionalCheckFailures.incrementAndGet()
        }

    // The delegate forwards this overload straight through, so it needs routing to the counted overload.
    override fun updateItem(updateItemRequest: Consumer<UpdateItemRequest.Builder>): CompletableFuture<UpdateItemResponse> =
        updateItem(UpdateItemRequest.builder().also { updateItemRequest.accept(it) }.build())
}

class TestAwsAdapter(server: ServerDefinition) : AwsAdapter(server) {
    val countingDynamo: CountingDynamoDbAsyncClient by lazy { CountingDynamoDbAsyncClient(embeddedDynamo()) }
    override val dynamo: DynamoDbAsyncClient
        get() = countingDynamo

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

    val webSocketChannels = HashMap<String, Channel<String>>()
    fun webSocketChannel(id: String): Channel<String> = webSocketChannels.getOrPut(id) { Channel() }
    override suspend fun apiGatewayWsDeleteConnection(request: DeleteConnectionRequest): DeleteConnectionResponse {
        println("delete ${request.connectionId()}")
        request.connectionId().let { webSocketChannels.remove(it)?.close() }
        return DeleteConnectionResponse.builder().apply {
            sdkHttpResponse(
                SdkHttpResponse.builder().statusCode(200).build()
            )
        }.build()
    }

    override suspend fun apiGatewayWsPostToConnection(request: PostToConnectionRequest): PostToConnectionResponse {
        println("post ${request.connectionId()} ${request.data().asUtf8String()}")
        webSocketChannels.getOrPut(request.connectionId()) { Channel() }.send(request.data().asUtf8String())
        return PostToConnectionResponse.builder().apply {
            sdkHttpResponse(
                SdkHttpResponse.builder().statusCode(200).build()
            )
        }.build()
    }

    private val pendingInvocations = Collections.synchronizedList(ArrayList<Deferred<*>>())

    /**
     * Waits for fire-and-forget Lambda invocations (didConnect, publish) to finish, so a test can act on a
     * settled socket rather than racing the connect handler.
     */
    fun awaitPendingInvocations(): Unit = runBlocking {
        while (true) {
            val pending = pendingInvocations.toList().ifEmpty { return@runBlocking }
            pending.awaitAll()
            pendingInvocations.removeAll(pending)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override suspend fun invokeLambda(invokeRequest: InvokeRequest): InvokeResponse {
        if (invokeRequest.invocationType() == InvocationType.EVENT) {
            pendingInvocations.add(GlobalScope.async {
                val out = ByteArrayOutputStream()
                this@TestAwsAdapter.handleRequest(
                    invokeRequest.payload().asInputStream(),
                    out,
                    TestLambdaContext()
                )
            })
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