// by Claude
package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.http.HttpStatus
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for WebSocketCloseReason.Code enum and related extensions.
 */
class WebSocketCloseTest {

    // ========== Enum Value Tests ==========

    @Test
    fun `WebSocketCloseReason.Code codes are correct`() {
        assertEquals(1000.toShort(), WebSocketClose.Code.NORMAL.code)
        assertEquals(1001.toShort(), WebSocketClose.Code.GOING_AWAY.code)
        assertEquals(1002.toShort(), WebSocketClose.Code.PROTOCOL_ERROR.code)
        assertEquals(1003.toShort(), WebSocketClose.Code.CANNOT_ACCEPT.code)
        assertEquals(1007.toShort(), WebSocketClose.Code.NOT_CONSISTENT.code)
        assertEquals(1008.toShort(), WebSocketClose.Code.VIOLATED_POLICY.code)
        assertEquals(1009.toShort(), WebSocketClose.Code.TOO_BIG.code)
        assertEquals(1010.toShort(), WebSocketClose.Code.NO_EXTENSION.code)
        assertEquals(1011.toShort(), WebSocketClose.Code.INTERNAL_ERROR.code)
        assertEquals(1012.toShort(), WebSocketClose.Code.SERVICE_RESTART.code)
        assertEquals(1013.toShort(), WebSocketClose.Code.TRY_AGAIN_LATER.code)
    }

    @Test
    fun `WebSocketCloseReason.Code values are all unique`() {
        val codes = WebSocketClose.Code.entries.map { it.code }
        assertEquals(codes.size, codes.distinct().size)
    }

    // ========== bestWebSocketCloseCode Tests ==========

    @Test
    fun `1xx status returns NORMAL`() {
        assertEquals(WebSocketClose.Code.NORMAL, HttpStatus.Continue.bestWebSocketCloseCode)
        assertEquals(WebSocketClose.Code.NORMAL, HttpStatus.SwitchingProtocols.bestWebSocketCloseCode)
    }

    @Test
    fun `2xx status returns NORMAL`() {
        assertEquals(WebSocketClose.Code.NORMAL, HttpStatus.OK.bestWebSocketCloseCode)
        assertEquals(WebSocketClose.Code.NORMAL, HttpStatus.Created.bestWebSocketCloseCode)
        assertEquals(WebSocketClose.Code.NORMAL, HttpStatus.NoContent.bestWebSocketCloseCode)
    }

    @Test
    fun `3xx status returns NORMAL`() {
        assertEquals(WebSocketClose.Code.NORMAL, HttpStatus.MovedPermanently.bestWebSocketCloseCode)
        assertEquals(WebSocketClose.Code.NORMAL, HttpStatus.Found.bestWebSocketCloseCode)
        assertEquals(WebSocketClose.Code.NORMAL, HttpStatus.TemporaryRedirect.bestWebSocketCloseCode)
    }

    @Test
    fun `4xx status returns VIOLATED_POLICY`() {
        assertEquals(WebSocketClose.Code.VIOLATED_POLICY, HttpStatus.BadRequest.bestWebSocketCloseCode)
        assertEquals(WebSocketClose.Code.VIOLATED_POLICY, HttpStatus.Unauthorized.bestWebSocketCloseCode)
        assertEquals(WebSocketClose.Code.VIOLATED_POLICY, HttpStatus.Forbidden.bestWebSocketCloseCode)
        assertEquals(WebSocketClose.Code.VIOLATED_POLICY, HttpStatus.NotFound.bestWebSocketCloseCode)
    }

    @Test
    fun `5xx status returns INTERNAL_ERROR`() {
        assertEquals(WebSocketClose.Code.INTERNAL_ERROR, HttpStatus.InternalServerError.bestWebSocketCloseCode)
        assertEquals(WebSocketClose.Code.INTERNAL_ERROR, HttpStatus.BadGateway.bestWebSocketCloseCode)
        assertEquals(WebSocketClose.Code.INTERNAL_ERROR, HttpStatus.ServiceUnavailable.bestWebSocketCloseCode)
    }

    // ========== webSocketCloseReason Tests ==========

    /**
     * The distinction that matters: a cancelled socket is being torn down by its scope — in practice
     * a server shutdown — and must not be reported as a server fault. Every engine used to derive
     * this from [HttpStatus.InternalServerError] regardless, so routine shutdowns showed up in
     * telemetry as 1011s and buried the real ones.
     */
    @Test
    fun `cancellation closes as GOING_AWAY, not an error`() {
        assertEquals(WebSocketClose.GOING_AWAY, CancellationException("shutting down").bestWebSocketCloseCode)
    }

    @Test
    fun `a cancellation subclass is still GOING_AWAY`() {
        // Coroutine cancellation arrives as subclasses (e.g. JobCancellationException), never as the
        // base type, so an equality check on the class would miss every real case.
        class Nested(message: String) : CancellationException(message)
        assertEquals(WebSocketClose.GOING_AWAY, Nested("child cancelled").bestWebSocketCloseCode)
    }

    /**
     * A handler's own `withTimeout` expiring arrives as a `CancellationException`, but it is a server
     * fault rather than a teardown. Reporting it as GOING_AWAY would bury it the same way deriving
     * every cancellation from a 500 used to bury shutdowns.
     */
    @Test
    fun `a timeout is an error, not a going-away`() = kotlinx.coroutines.runBlocking {
        val timeout = try {
            kotlinx.coroutines.withTimeout(1) { kotlinx.coroutines.delay(1_000); null }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            e
        }
        assertEquals(WebSocketClose.Code.INTERNAL_ERROR, (timeout as Throwable).bestWebSocketCloseCode)
    }

    @Test
    fun `an HttpStatusException keeps its status mapping`() {
        assertEquals(WebSocketClose.Code.VIOLATED_POLICY, HttpStatusException(HttpStatus.Forbidden).bestWebSocketCloseCode)
        assertEquals(WebSocketClose.Code.INTERNAL_ERROR, HttpStatusException(HttpStatus.BadGateway).bestWebSocketCloseCode)
    }

    @Test
    fun `an ordinary failure is still INTERNAL_ERROR`() {
        assertEquals(WebSocketClose.Code.INTERNAL_ERROR, RuntimeException("boom").bestWebSocketCloseCode)
    }
}
