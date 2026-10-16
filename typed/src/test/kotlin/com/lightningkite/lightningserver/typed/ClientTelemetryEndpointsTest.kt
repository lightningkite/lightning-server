package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.telemetrySettings
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.handle
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.TestRunner
import com.lightningkite.lightningserver.runtime.test.testBlocking
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.typed.sdk.module
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
        val cappedTelemetry = path.path("capped") module ClientTelemetryEndpoints()
    }

    private fun span(owner: String) = RecordedSpan(
        traceId = "0af7651916cd43dd8448eb211c80319c", spanId = "b7ad6b7169203331", owner = owner, operation = "load",
        start = Clock.System.now(), end = Clock.System.now(),
        attributes = telemetryAttributesOf(TelemetryKeys.User.id to "spoofed", TelemetryKeys.App.screenName to "Home"),
        dimensions = setOf(), outcome = RecordedSpan.Outcome.Ok, sampled = true,
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
    fun distinctOwnersAreCapped() = withServer {
        val ingest = TestServer.cappedTelemetry.ingest
        ingest.test(null, batch(*Array(500) { span("owner$it") }))
        assertFailsWith<BadRequestException> { ingest.test(null, batch(span("one-more"))) }
        ingest.test(null, batch(span("owner0"))) // already-known names keep working
    }

    @Test
    fun requestSpanContinuesTheClientsTrace() = withServer {
        synchronized(Recording.traceParents) { Recording.traceParents.clear() }
        val header = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
        serverRuntime.handle(
            HttpRequest<PathSpec>(
                path = RawHttpEndpoint(asString = "/telemetry/ingest", method = HttpMethod.POST),
                queryParameters = QueryParameters.EMPTY,
                headers = HttpHeaders { add("traceparent", header) },
                domain = "example.com",
                protocol = "https",
                sourceIp = "local",
                requestId = generateRequestId(),
                body = TypedData.text("{}", MediaType.Application.Json),
            )
        )
        assertEquals(header, Recording.traceParents.first())
    }
}
