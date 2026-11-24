package com.lightningkite.lightningserver.engine.awsserverless

import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.lightningserver.http.QueryParameters
import com.lightningkite.lightningserver.pathing.RawWebSocketPath
import com.lightningkite.lightningserver.runtime.Execution
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.services.data.Unsafe
import com.lightningkite.services.data.UuidV7
import kotlinx.serialization.builtins.NothingSerializer
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** How a stored row is read, without DynamoDB: [AwsAdapterWsLegacyStateRowTest] covers the same through a socket. */
class AwsWebSocketDynamoDbRowTest {
    private val serialization = Serialization()

    // Never called: decodeRow only reads the item it is given.
    private val dynamo = AwsWebSocketDynamoDb(
        DynamoDbAsyncClient.builder()
            .region(Region.US_WEST_2)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("unused", "unused")))
            .build(),
        "test",
        serialization.kotlinWireFormat,
    )

    @OptIn(Unsafe::class, com.lightningkite.lightningserver.InternalLightningServerApi::class)
    private val request = WebSocketConnectRequest<Nothing>(
        path = RawWebSocketPath.fromPathSegments(PathSegments.parse("")),
        queryParameters = QueryParameters(listOf("path" to "/echo")),
        engineSocketId = "connection",
        socketId = Execution.ID(UuidV7.generate()),
    )

    private fun bytes(value: ByteArray) = AttributeValue.fromB(SdkBytes.fromByteArray(value))

    @Test
    fun rowInNewAttributesDecodes() {
        val state = byteArrayOf(1, 2, 3)
        val row = dynamo.decodeRow(
            "connection", mapOf(
                "wsSocketId" to AttributeValue.fromS("connection"),
                "wsWireState" to bytes(state),
                "wsWireRequest" to bytes(serialization.kotlinWireFormat.encodeToByteArray(WebSocketConnectRequest.serializer(NothingSerializer()), request)),
            )
        )
        val current = assertIs<SocketRow.Current>(row)
        assertEquals(state.toList(), current.state.toList())
        assertEquals(request, current.connectRequest)
    }

    @Test
    fun rowWithOnlyOldAttributesIsStale() {
        val row = dynamo.decodeRow(
            "connection", mapOf(
                "wsSocketId" to AttributeValue.fromS("connection"),
                "wsState" to bytes(byteArrayOf(1, 2, 3)),
                "wsRequest" to bytes(serialization.oldKotlinBytesFormat.encodeToByteArray(WebSocketConnectRequest.serializer(NothingSerializer()), request)),
            )
        )
        assertIs<SocketRow.Legacy>(row)
    }
}
