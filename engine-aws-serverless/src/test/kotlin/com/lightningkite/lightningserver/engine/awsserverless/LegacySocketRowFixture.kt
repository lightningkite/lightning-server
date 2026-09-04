package com.lightningkite.lightningserver.engine.awsserverless

import com.lightningkite.lightningserver.AnonType
import com.lightningkite.lightningserver.data.SerializableCache
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.QueryParameters
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.RawWebSocketPath
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.NothingSerializer
import kotlinx.serialization.builtins.serializer
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/**
 * Writes socket rows in the shape a deployment at fork point `4910a921` wrote them.
 *
 * This exists because a row produced by today's serializer cannot reproduce the failure a real one
 * causes.  The stored connect request is a positional `KotlinBytesFormat` blob, and this branch removed
 * two properties from [com.lightningkite.lightningserver.websockets.WebSocketConnectRequest], so the
 * only way to test the rolling-deploy path honestly is to encode the old layout.
 *
 * [LegacyWebSocketConnectRequest] mirrors that commit's type element for element, **including which
 * elements carried defaults**: `KotlinBytesFormat` writes an index marker for every optional element, so
 * the optionality pattern is part of the wire format rather than a detail of the source.  The removed
 * `requestId` (index 6, no default) and `parentRequestId` (index 7) are what make the blob unreadable
 * today - the old layout has 11 elements where the current descriptor has 9, and decoding walks off the
 * end of it.
 */
@Serializable
class LegacyWebSocketConnectRequest<PATH : PathSpec>(
    val path: RawWebSocketPath<PATH>,
    val queryParameters: QueryParameters = QueryParameters.EMPTY,
    val headers: HttpHeaders = HttpHeaders.EMPTY,
    val domain: String = "",
    val protocol: String = "",
    val sourceIp: String = "",
    /** Removed on this branch.  Non-optional then, so it carries no index marker - keep it that way. */
    val requestId: String,
    /** Removed on this branch. */
    val parentRequestId: String? = null,
    val upstreamRequestId: String? = null,
    val cache: SerializableCache = SerializableCache(),
    val engineSocketId: String? = null,
)

/**
 * The legacy shape of [com.lightningkite.lightningserver.websockets.QueryParamWebSocketHandlerData],
 * which embeds a connect request and is therefore undecodable for the same reason the request is.
 */
@Serializable
class LegacyQueryParamWebSocketHandlerData(
    val request: LegacyWebSocketConnectRequest<*>,
    val underlyingData: AnonType,
)

/**
 * The legacy shape of [AwsAdapterWs.WebSocketDidConnect]: no `initiator`.
 *
 * The payload travels as JSON, not as a positional blob, and the adapter's `Json` sets
 * `ignoreUnknownKeys`, so the extra `requestId` and `parentRequestId` here are tolerated on arrival.
 * The missing `initiator` is the whole failure.
 */
@Serializable
class LegacyWebSocketDidConnect(
    val socketId: String,
    val connection: LegacyWebSocketConnectRequest<*>,
    val storage: AnonType,
)

// Column names, duplicated from AwsWebSocketDynamoDb's private companion: writing the row by hand is
// the point of this fixture, so the literal names are part of it.
internal const val socketIdColumn = "wsSocketId"
internal const val stateColumn = "wsState"
internal const val requestColumn = "wsRequest"
internal const val initiatorColumn = "wsInitiator"
private const val expireColumn = "wsExpire"

internal fun TestAwsAdapter.stateTableName(): String = ws.webSocketDynamo.baseTableName + "-ws-state"

internal fun TestAwsAdapter.legacyConnectRequest(connectionId: String) = LegacyWebSocketConnectRequest<Nothing>(
    // AWS sockets all arrive at "/" and carry their real path in a query parameter.
    path = RawWebSocketPath(""),
    queryParameters = QueryParameters(listOf("path" to "/echo")),
    requestId = "legacy-request-id",
    engineSocketId = connectionId,
)

/**
 * Replaces [connectionId]'s state row with one written the way the fork point wrote it.
 *
 * Overwrites rather than inserts, so a test can connect normally first - keeping the subscription rows
 * that `didConnect` registered - and then age only the state row.  `wsInitiator` is written only if
 * [initiator] is given, which models the narrower case of a row whose column set is current but whose
 * blob is not.
 */
internal fun TestAwsAdapter.putLegacySocketRow(connectionId: String, initiator: AttributeValue? = null) {
    val encoding = internalSerialization.kotlinBytesFormat
    val request = legacyConnectRequest(connectionId)
    val storage = LegacyQueryParamWebSocketHandlerData(
        request = request,
        underlyingData = AnonType(encoding, Unit, Unit.serializer()),
    )
    runBlocking {
        ws.webSocketDynamo.ensureTables()
        countingDynamo.putItem {
            it.tableName(stateTableName())
            it.item(
                buildMap {
                    put(socketIdColumn, AttributeValue.fromS(connectionId))
                    put(
                        stateColumn, AttributeValue.fromB(
                            SdkBytes.fromByteArray(
                                encoding.encodeToByteArray(
                                    LegacyQueryParamWebSocketHandlerData.serializer(),
                                    storage
                                )
                            )
                        )
                    )
                    put(
                        requestColumn, AttributeValue.fromB(
                            SdkBytes.fromByteArray(
                                encoding.encodeToByteArray(LegacyWebSocketConnectRequest.serializer(NothingSerializer()), request)
                            )
                        )
                    )
                    put(
                        expireColumn,
                        AttributeValue.fromN(Clock.System.now().plus(8.hours).epochSeconds.toString())
                    )
                    initiator?.let { put(initiatorColumn, it) }
                }
            )
        }.await()
    }
}

/** The legacy `didConnect` payload the fork point's `${'$'}connect` would have fired for this socket. */
internal fun TestAwsAdapter.legacyDidConnectPayload(connectionId: String): ByteArray {
    val encoding = internalSerialization.kotlinBytesFormat
    return internalSerialization.json.encodeToString(
        LegacyWebSocketDidConnect.serializer(),
        LegacyWebSocketDidConnect(
            socketId = connectionId,
            connection = legacyConnectRequest(connectionId),
            storage = AnonType(encoding, Unit, Unit.serializer()),
        )
    ).toByteArray()
}
