package com.lightningkite.lightningserver.engine.ktor

import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.definition.loggingSettings
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.definition.telemetrySettings
import com.lightningkite.lightningserver.engine.local.engineCache
import com.lightningkite.lightningserver.engine.local.enginePubSub
import com.lightningkite.lightningserver.settings.set
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies that the Ktor engine correctly streams a [com.lightningkite.services.data.Data.Sink]
 * response body to the client.
 *
 * Regression coverage for the Data.Sink branch:
 *   is Data.Sink -> call.respondBytesWriter { body.emit(this.asSink().buffered()) }
 *
 * The risk is that the buffered sink wrapping the Ktor ByteWriteChannel does not flush
 * on completion, in which case the tail of the payload would be dropped (a recent gzip
 * bug had exactly this shape on the response-compression path).
 */
class KtorSinkResponseTest {

    private fun configureEngine(): KtorEngine {
        val engine = KtorEngine(TestServerBuilder.build())
        engine.settings.run {
            generalSettings.useDefault()
            secretBasis.useDefault()
            telemetrySettings.useDefault()
            loggingSettings.useDefault()
            enginePubSub.useDefault()
            engineCache.useDefault()
            ktorRunConfig set KtorRuntimeSettings(host = "127.0.0.1", port = 0)
        }
        engine.settings.readyUsingDefaults()
        return engine
    }

    private fun withEngine(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val engine = configureEngine()
        application { with(engine) { adapt() } }
        block()
    }

    @Test
    fun small_sink_body_streams_full_content() = runTest {
        withEngine {
            val response = client.get("/sink")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("sink-content", response.bodyAsText())
            assertEquals("text/plain", response.contentType()?.contentType + "/" + response.contentType()?.contentSubtype)
        }
    }

    @Test
    fun large_sink_body_is_fully_flushed() = runTest {
        // Catches a missing flush/close on the buffered sink: with 100k bytes, any
        // unflushed tail in the kotlinx.io buffer would be observable as a short read.
        withEngine {
            val response = client.get("/bigsink")
            assertEquals(HttpStatusCode.OK, response.status)
            val text = response.bodyAsText()
            assertEquals(100_000, text.length)
            assertEquals("x".repeat(100_000), text)
        }
    }

    @Test
    fun chunked_sink_writes_preserve_order() = runTest {
        withEngine {
            val response = client.get("/chunkedsink")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("alpha|beta|gamma", response.bodyAsText())
        }
    }

    @Test
    fun blocking_source_response_streams_full_content() = runTest {
        // Data.Source response branch: streamed off the event loop (withContext(IO) + asSink bridge), not on it.
        withEngine {
            val response = client.get("/source")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("streamed-content", response.bodyAsText())
        }
    }

    @Test
    fun suspending_source_response_streams_full_content() = runTest {
        // Cooperative Data.SuspendingSource response branch: streamed via KtorChannelSuspendingSink (fully non-blocking).
        withEngine {
            val response = client.get("/suspendingsource")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("suspending-content", response.bodyAsText())
        }
    }

    @Test
    fun large_suspending_producer_is_fully_delivered() = runTest {
        // 100k cooperative producer body: any dropped tail or backpressure-handling bug in the SuspendingSink
        // adapter would show up as a short read.
        withEngine {
            val response = client.get("/suspendingproducer")
            assertEquals(HttpStatusCode.OK, response.status)
            val text = response.bodyAsText()
            assertEquals(100_000, text.length)
            assertEquals("y".repeat(100_000), text)
        }
    }

    @Test
    fun sink_response_uses_chunked_transfer_encoding() = runTest {
        // Data.Sink has no known size, so Ktor should fall back to chunked transfer.
        // (Content-Length must NOT be set; respondBytesWriter is a streaming response.)
        withEngine {
            val response = client.get("/bigsink")
            assertEquals(null, response.headers[HttpHeaders.ContentLength])
        }
    }
}
