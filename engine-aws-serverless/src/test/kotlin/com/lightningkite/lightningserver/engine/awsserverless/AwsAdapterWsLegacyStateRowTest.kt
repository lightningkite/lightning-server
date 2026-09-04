package com.lightningkite.lightningserver.engine.awsserverless

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.lightningserver.websockets.request
import com.lightningkite.lightningserver.websockets.text
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.NothingSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A socket row written by an earlier deployment cannot be served or attributed by this one, and no later
 * phase can repair it: both the stored connect request and the initiator are written once, at
 * `${'$'}connect`, under whatever code was deployed then.  Every phase after `${'$'}connect` therefore
 * has to end such a socket rather than fail on it, or a rolling deploy breaks every socket open across
 * it.
 *
 * Two flavours of aged row are covered, because both are real:
 * - a **genuine legacy row**, written by [putLegacySocketRow] in the fork point's own layout, which the
 *   current serializer cannot decode at all;
 * - a **row whose column set is older** than its blob, produced by removing `wsInitiator` from a row this
 *   deployment wrote.
 */
class AwsAdapterWsLegacyStateRowTest {

    object SampleServer : ServerBuilder() {
        val broadcast = path.path("broadcast").topic(String.serializer())

        /**
         * Counts disconnect-handler runs so the healthy path can be shown to still reach it.
         *
         * A count rather than a socket id because [WebSocketConnectRequest.engineSocketId] is null in
         * here: QueryParamWebSocketHandler, which every AWS socket goes through, rebuilds the request
         * without it.  Each test resets this, and only one test disconnects a healthy socket.
         */
        val disconnectHandlerRuns: AtomicInteger = AtomicInteger()

        val echo = path.path("echo") bind WebSocketHandler(
            storageSerializer = Unit.serializer(),
            willConnect = { Unit },
            didConnect = { subscribe(broadcast.request()) },
            topicHandlers = { broadcast bind { send(WebSocketFrame("topic:" + it.value)) } },
            messageFromClient = { send(WebSocketFrame("echo:" + it.text)) },
            disconnect = { disconnectHandlerRuns.incrementAndGet() },
        )

        init {
            registerBasicMediaTypeCoders()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun adapter(): TestAwsAdapter = TestAwsAdapter(SampleServer.build())

    /** The raw state row, or null if the socket is no longer tracked. */
    private fun TestAwsAdapter.stateRow(connectionId: String): Map<String, AttributeValue>? = runBlocking {
        countingDynamo.getItem {
            it.tableName(stateTableName())
            it.key(mapOf(socketIdColumn to AttributeValue.fromS(connectionId)))
            it.consistentRead(true)
        }.await().takeIf { it.hasItem() }?.item()
    }

    /** Ages only the column set: the blob stays current, but the initiator column goes away. */
    private fun TestAwsAdapter.stripInitiator(connectionId: String) {
        assertTrue(
            stateRow(connectionId)!!.containsKey(initiatorColumn),
            "A freshly connected socket must have an initiator, or this fixture is testing nothing"
        )
        runBlocking {
            countingDynamo.updateItem {
                it.tableName(stateTableName())
                it.key(mapOf(socketIdColumn to AttributeValue.fromS(connectionId)))
                it.updateExpression("REMOVE $initiatorColumn")
            }.await()
        }
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
     * Decodes one lambda invocation's response.
     *
     * A handler that throws instead of returning leaves the output stream empty, which is what reading an
     * aged row used to do on the client-message route, so an empty response is reported as its own
     * failure rather than a confusing deserialization error.
     */
    private fun decodeResponse(raw: ByteArray, what: String): APIGatewayV2HTTPResponse {
        val text = raw.decodeToString()
        assertTrue(text.isNotBlank(), "The lambda produced no response for $what, meaning the handler threw")
        return json.decodeFromString(APIGatewayV2HTTPResponse.serializer(), text)
    }

    private fun TestAwsAdapter.invoke(event: APIGatewayV2WebSocketRequest): APIGatewayV2HTTPResponse =
        decodeResponse(handleRequest(event), "route '${event.requestContext.routeKey}'")

    /**
     * Connects a socket to /echo and lets the asynchronous didConnect settle, so the row and its
     * subscription are in place before the test ages them.  Buffers outgoing frames so a send the test
     * does not expect fails an assertion instead of blocking the engine on a rendezvous channel.
     */
    private fun TestAwsAdapter.connect(connectionId: String): Channel<String> {
        val channel = Channel<String>(Channel.UNLIMITED)
        webSocketChannels[connectionId] = channel
        val base = baseMessage(connectionId)
        val response = invoke(
            base.copy(
                multiValueQueryStringParameters = mapOf("path" to listOf("/echo")),
                requestContext = base.requestContext.copy(routeKey = "\$connect")
            )
        )
        assertEquals(200, response.statusCode, "Connect should have succeeded")
        awaitPendingInvocations()
        return channel
    }

    private fun clientMessage(connectionId: String, body: String) = baseMessage(connectionId).let {
        it.copy(requestContext = it.requestContext.copy(routeKey = "\$default"), body = body)
    }

    private fun disconnect(connectionId: String) = baseMessage(connectionId).let {
        it.copy(requestContext = it.requestContext.copy(routeKey = "\$disconnect"))
    }

    private fun TestAwsAdapter.publishToBroadcast() {
        runBlocking {
            sendWebSocketSubscriptionMessage(WebSocketSubscriptionMessage(SampleServer.broadcast, listOf(), "hello"))
        }
        awaitPendingInvocations()
    }

    /** The socket was ended: closed, its row dropped, and nothing delivered to it. */
    private fun assertSocketEnded(adapter: TestAwsAdapter, connectionId: String, channel: Channel<String>) {
        assertTrue(
            connectionId in adapter.closedConnections,
            "The socket must be closed so the client reconnects and gets a current row"
        )
        assertNull(
            adapter.stateRow(connectionId),
            "The row must be cleaned up, or every later phase repeats this"
        )
        assertNull(
            channel.tryReceive().getOrNull(),
            "Nothing should be delivered to a socket this deployment cannot serve"
        )
    }

    /**
     * The fixture has to be genuinely unreadable, or every test built on it is theatre.  If
     * [LegacyWebSocketConnectRequest] ever drifts into a layout the current serializer accepts, this
     * fails here rather than quietly turning the rest of the file green for the wrong reason.
     */
    @Test
    fun legacyBlobCannotBeDecodedByTheCurrentSerializer() {
        val adapter = adapter()
        val encoding = adapter.internalSerialization.kotlinBytesFormat
        val blob = encoding.encodeToByteArray(
            LegacyWebSocketConnectRequest.serializer(NothingSerializer()),
            adapter.legacyConnectRequest("fixture-check")
        )
        val thrown = assertFails {
            encoding.decodeFromByteArray(WebSocketConnectRequest.serializer(NothingSerializer()), blob)
        }
        assertTrue(
            thrown is SerializationException || thrown is IOException,
            "A fork-point blob must fail to decode as a schema mismatch, not as $thrown"
        )
    }

    @Test
    fun clientMessageOnLegacyRowEndsTheSocket() {
        val adapter = adapter()
        val connectionId = "legacy-row-client-message"
        val channel = adapter.connect(connectionId)
        adapter.putLegacySocketRow(connectionId)

        val response = adapter.invoke(clientMessage(connectionId, "Ping!"))
        adapter.awaitPendingInvocations()

        assertEquals(
            204,
            response.statusCode,
            "An unusable row is discarded, not reported as a server failure: ${response.body}"
        )
        assertSocketEnded(adapter, connectionId, channel)
    }

    /**
     * A row whose blob is from the fork point but which does carry an initiator - the case where only the
     * stored request is stale.  This is the one that reaches the decode failure itself rather than
     * short-circuiting on the absent column.
     */
    @Test
    fun clientMessageOnLegacyBlobWithCurrentInitiatorEndsTheSocket() {
        val adapter = adapter()
        val connectionId = "legacy-blob-client-message"
        val channel = adapter.connect(connectionId)
        adapter.putLegacySocketRow(connectionId, initiator = adapter.stateRow(connectionId)!![initiatorColumn]!!)

        val response = adapter.invoke(clientMessage(connectionId, "Ping!"))
        adapter.awaitPendingInvocations()

        assertEquals(204, response.statusCode, "A row whose blob no longer decodes is discarded: ${response.body}")
        assertSocketEnded(adapter, connectionId, channel)
    }

    @Test
    fun clientMessageOnRowMissingOnlyTheInitiatorColumnEndsTheSocket() {
        val adapter = adapter()
        val connectionId = "no-initiator-client-message"
        val channel = adapter.connect(connectionId)
        adapter.stripInitiator(connectionId)

        val response = adapter.invoke(clientMessage(connectionId, "Ping!"))
        adapter.awaitPendingInvocations()

        assertEquals(
            204,
            response.statusCode,
            "An unattributable socket is discarded, not reported as a server failure: ${response.body}"
        )
        assertSocketEnded(adapter, connectionId, channel)
    }

    @Test
    fun disconnectOnLegacyRowIsDiscardedNotFailed() {
        val adapter = adapter()
        val connectionId = "legacy-row-disconnect"
        adapter.connect(connectionId)
        adapter.putLegacySocketRow(connectionId)
        SampleServer.disconnectHandlerRuns.set(0)

        val response = adapter.invoke(disconnect(connectionId))
        adapter.awaitPendingInvocations()

        assertEquals(
            204,
            response.statusCode,
            "A disconnect that cannot be attributed is discarded, not reported as a failure: ${response.body}"
        )
        assertNull(adapter.stateRow(connectionId), "The row must be cleaned up")
        assertTrue(
            connectionId !in adapter.closedConnections,
            "The socket is already going away, so no close call should be made"
        )
        assertEquals(
            0,
            SampleServer.disconnectHandlerRuns.get(),
            "The disconnect phase must not be recorded under a fabricated initiator"
        )
    }

    @Test
    fun disconnectOnRowMissingOnlyTheInitiatorColumnIsDiscarded() {
        val adapter = adapter()
        val connectionId = "no-initiator-disconnect"
        adapter.connect(connectionId)
        adapter.stripInitiator(connectionId)
        SampleServer.disconnectHandlerRuns.set(0)

        val response = adapter.invoke(disconnect(connectionId))
        adapter.awaitPendingInvocations()

        assertEquals(204, response.statusCode, "Expected the disconnect to be discarded: ${response.body}")
        assertNull(adapter.stateRow(connectionId), "The row must be cleaned up")
        assertTrue(connectionId !in adapter.closedConnections, "No close call should be made")
        assertEquals(0, SampleServer.disconnectHandlerRuns.get(), "The disconnect phase must not be recorded")
    }

    @Test
    fun subscriptionPushToLegacyRowEndsTheSocket() {
        val adapter = adapter()
        val connectionId = "legacy-row-subscription"
        val channel = adapter.connect(connectionId)
        adapter.putLegacySocketRow(connectionId)

        adapter.publishToBroadcast()

        assertSocketEnded(adapter, connectionId, channel)
    }

    @Test
    fun subscriptionPushToRowMissingOnlyTheInitiatorColumnEndsTheSocket() {
        val adapter = adapter()
        val connectionId = "no-initiator-subscription"
        val channel = adapter.connect(connectionId)
        adapter.stripInitiator(connectionId)

        adapter.publishToBroadcast()

        assertSocketEnded(adapter, connectionId, channel)
    }

    /**
     * `didConnect` is a separate Lambda invocation, so its initiator travels in the payload rather than
     * the row.  A payload fired by the fork point has no `initiator` at all, and the field was required,
     * so the whole payload used to fail to decode - silently, since the outer handler writes no response
     * and Lambda records the async invocation as a success.
     */
    @Test
    fun didConnectWithoutInitiatorEndsTheSocket() {
        val adapter = adapter()
        val connectionId = "legacy-did-connect"
        val channel = adapter.connect(connectionId)
        adapter.putLegacySocketRow(connectionId)

        val response = decodeResponse(
            adapter.handleRequest(adapter.legacyDidConnectPayload(connectionId)),
            "a didConnect payload with no initiator"
        )
        adapter.awaitPendingInvocations()

        assertEquals(204, response.statusCode, "The payload must decode and be discarded: ${response.body}")
        assertSocketEnded(adapter, connectionId, channel)
    }

    /**
     * Guards the healthy path against all of the above: a socket with a current row must still receive
     * client messages, subscription pushes, and a real disconnect.
     */
    @Test
    fun socketWithACurrentRowStillWorksEndToEnd() {
        val adapter = adapter()
        val connectionId = "healthy-socket"
        val channel = adapter.connect(connectionId)
        SampleServer.disconnectHandlerRuns.set(0)

        assertEquals(200, adapter.invoke(clientMessage(connectionId, "Ping!")).statusCode)
        adapter.awaitPendingInvocations()
        assertEquals("echo:Ping!", channel.tryReceive().getOrNull(), "The message should have echoed back")

        adapter.publishToBroadcast()
        assertEquals("topic:hello", channel.tryReceive().getOrNull(), "The subscription push should have arrived")

        assertEquals(200, adapter.invoke(disconnect(connectionId)).statusCode)
        adapter.awaitPendingInvocations()
        assertEquals(1, SampleServer.disconnectHandlerRuns.get(), "The disconnect handler should have run")
        assertNull(adapter.stateRow(connectionId), "Disconnect cleans up the row")
        assertTrue(
            connectionId !in adapter.closedConnections,
            "A healthy socket is never force-closed by the engine"
        )
    }
}
