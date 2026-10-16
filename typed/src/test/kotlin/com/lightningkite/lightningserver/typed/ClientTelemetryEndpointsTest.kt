package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.telemetrySettings
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.handle
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.TestRunner
import com.lightningkite.lightningserver.runtime.test.testBlocking
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.typed.sdk.FetcherSdk
import com.lightningkite.lightningserver.typed.sdk.SDK.writeUsingDefaultSettings
import com.lightningkite.lightningserver.typed.sdk.SdkModule
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.services.kfile.KFile
import kotlin.test.assertTrue
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.sdk.User
import com.lightningkite.services.Namespaced
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.HasId
import com.lightningkite.services.telemetry.RecordedSpan
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryBackend
import com.lightningkite.services.telemetry.TelemetryBatch
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.telemetry.TelemetryKeys
import com.lightningkite.services.telemetry.TelemetryTrace
import com.lightningkite.services.telemetry.TraceParent
import com.lightningkite.services.telemetry.telemetryAttributesOf
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.uuid.Uuid

class ClientTelemetryEndpointsTest {
    private object Recording : TelemetryBackend by TelemetryBackend.Noop {
        val batches = mutableListOf<TelemetryBatch>()
        val traceParents = mutableListOf<String?>()

        init {
            TelemetryBackend.Settings.register("client-ingest-recording") { _, _, _ -> this }
        }

        override fun ingest(batch: TelemetryBatch) { synchronized(batches) { batches += batch } }

        override suspend fun <T> span(owner: Namespaced, opName: String, attributes: TelemetryAttributes, dimensions: Set<TelemetryKey<*>>, action: suspend (TelemetryTrace) -> T): T {
            synchronized(traceParents) { traceParents += coroutineContext[TraceParent]?.header }
            return TelemetryBackend.Noop.span(owner, opName, attributes, dimensions, action)
        }
    }

    object TestServer : ServerBuilder() {
        init { registerBasicMediaTypeCoders() }
        val telemetry = path.path("telemetry") module ClientTelemetryEndpoints()
        val ping = path.path("ping").get bind HttpHandler<PathSpec0> { HttpResponse.plainText("pong") }
    }

    private fun span(owner: String) = RecordedSpan(
        traceId = "0af7651916cd43dd8448eb211c80319c", spanId = "b7ad6b7169203331", owner = owner, operation = "load",
        start = Clock.System.now(), end = Clock.System.now(),
        attributes = telemetryAttributesOf(TelemetryKeys.User.id to "spoofed", TelemetryKeys.App.screenName to "Home"),
        outcome = RecordedSpan.Outcome.Ok,
    )

    private fun batch(vararg spans: RecordedSpan) = TelemetryBatch(
        resource = telemetryAttributesOf(TelemetryKeys.User.id to "spoofed", TelemetryKeys.Service.name to "app"),
        sentAt = Clock.System.now(),
        spans = spans.toList(),
    )

    private fun withServer(block: suspend context(TestRunner<TestServer>) TestServer.() -> Unit) = TestServer.testBlocking(settings = {
        Recording
        telemetrySettings.set(TelemetryBackend.Settings(url = "client-ingest-recording"))
    }) {
        synchronized(Recording.batches) { Recording.batches.clear() }
        block()
    }

    @Test
    fun anonymousIngestHasNoUserAndDropsSpoofedOnes() = withServer {
        TestServer.telemetry.ingest.test(null, batch(span("screen")))

        val received = Recording.batches.single()
        assertNull(received.resource[TelemetryKeys.User.id])
        assertEquals("app", received.resource[TelemetryKeys.Service.name])
        assertNull(received.spans.single().attributes[TelemetryKeys.User.id])
        assertEquals("Home", received.spans.single().attributes[TelemetryKeys.App.screenName])
    }

    @Test
    fun authenticatedIngestGetsTheCallersId() = withServer {
        val id = Uuid.random()
        @Suppress("UNCHECKED_CAST")
        val auth = Authentication(User, id, sessionId = null) as Authentication<HasId<*>>
        TestServer.telemetry.ingest.test(auth, batch(span("screen")))

        assertEquals(id.toString(), Recording.batches.single().resource[TelemetryKeys.User.id])
    }

    @Test
    fun oversizedBatchIsRejected() = withServer {
        assertFailsWith<BadRequestException> {
            TestServer.telemetry.ingest.test(null, batch(*Array(2001) { span("screen") }))
        }
    }

    @Test
    fun malformedOwnerIsRejected() = withServer {
        assertFailsWith<BadRequestException> {
            TestServer.telemetry.ingest.test(null, batch(span("bad name {}")))
        }
    }

    @Test
    fun malformedAttributeKeyIsRejected() = withServer {
        val bad = span("screen").copy(attributes = telemetryAttributesOf(TelemetryKey.OfString("\u001b[31mfake") to "x"))
        assertFailsWith<BadRequestException> { TestServer.telemetry.ingest.test(null, batch(bad)) }
    }

    @Test
    fun tooManyAttributesOnARecordIsRejected() = withServer {
        val wide = span("screen").copy(attributes = TelemetryAttributes(List(129) { TelemetryKey.OfString("key$it") to "x" }))
        assertFailsWith<BadRequestException> { TestServer.telemetry.ingest.test(null, batch(wide)) }
    }

    @Test
    fun malformedIdsAreRejected() = withServer {
        assertFailsWith<BadRequestException> { TestServer.telemetry.ingest.test(null, batch(span("screen").copy(traceId = "not-hex"))) }
        assertFailsWith<BadRequestException> { TestServer.telemetry.ingest.test(null, batch(span("screen").copy(parentSpanId = "123"))) }
    }

    @Test
    fun overlongAttributeValueIsRejected() = withServer {
        val long = span("screen").copy(attributes = telemetryAttributesOf(TelemetryKeys.Url.path to "x".repeat(4097)))
        assertFailsWith<BadRequestException> { TestServer.telemetry.ingest.test(null, batch(long)) }
    }

    @Test
    fun generatedSdkTakesTheSharedBatchType() {
        val folder = KFile("./build/test-sdk-client-telemetry")
        FetcherSdk(
            packageName = "com.example.test",
            rootInfo = SdkModule.Info("TestApi"),
            fileStructure = FetcherSdk.Structure.SingleFile("sdk.kt"),
        ).writeUsingDefaultSettings(TestServer, folder)

        val sdk = folder.then("sdk.kt").readString()
        assertTrue("val clientTelemetry: ClientTelemetryApi" in sdk, sdk)
        assertTrue("suspend fun ingest(input: com.lightningkite.services.telemetry.TelemetryBatch)" in sdk, sdk)
    }

    @Test
    fun requestSpanContinuesTheClientsTrace() = withServer {
        synchronized(Recording.traceParents) { Recording.traceParents.clear() }
        val header = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
        serverRuntime.handle(request("/ping", HttpMethod.GET, HttpHeaders { add("traceparent", header) }))
        assertEquals(header, Recording.traceParents.first())
    }

    @Test
    fun theIngestRequestIsNotTraced() = withServer {
        synchronized(Recording.traceParents) { Recording.traceParents.clear() }
        val body = serverRuntime.externalSerialization.json.encodeToString(TelemetryBatch.serializer(), batch(span("screen")))
        serverRuntime.handle(request("/telemetry/ingest", HttpMethod.POST, HttpHeaders.EMPTY, TypedData.text(body, MediaType.Application.Json)))

        assertEquals(1, Recording.batches.size, "the batch was ingested")
        assertTrue(Recording.traceParents.isEmpty(), "but no span was started for the request")
    }

    private fun request(path: String, method: HttpMethod, headers: HttpHeaders, body: TypedData? = null) = HttpRequest<PathSpec>(
        path = RawHttpEndpoint(asString = path, method = method),
        queryParameters = QueryParameters.EMPTY,
        headers = headers,
        domain = "example.com",
        protocol = "https",
        sourceIp = "local",
        requestId = generateRequestId(),
        body = body,
    )
}
