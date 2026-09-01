// by Claude
package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.http.HttpStatus
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for WebSocketClose enum and related extensions.
 */
class WebSocketCloseTest {

    // ========== Enum Value Tests ==========

    @Test
    fun `WebSocketClose codes are correct`() {
        assertEquals(1000.toShort(), WebSocketClose.NORMAL.code)
        assertEquals(1001.toShort(), WebSocketClose.GOING_AWAY.code)
        assertEquals(1002.toShort(), WebSocketClose.PROTOCOL_ERROR.code)
        assertEquals(1003.toShort(), WebSocketClose.CANNOT_ACCEPT.code)
        assertEquals(1007.toShort(), WebSocketClose.NOT_CONSISTENT.code)
        assertEquals(1008.toShort(), WebSocketClose.VIOLATED_POLICY.code)
        assertEquals(1009.toShort(), WebSocketClose.TOO_BIG.code)
        assertEquals(1010.toShort(), WebSocketClose.NO_EXTENSION.code)
        assertEquals(1011.toShort(), WebSocketClose.INTERNAL_ERROR.code)
        assertEquals(1012.toShort(), WebSocketClose.SERVICE_RESTART.code)
        assertEquals(1013.toShort(), WebSocketClose.TRY_AGAIN_LATER.code)
    }

    @Test
    fun `WebSocketClose values are all unique`() {
        val codes = WebSocketClose.entries.map { it.code }
        assertEquals(codes.size, codes.distinct().size)
    }

    // ========== bestWebSocketCloseCode Tests ==========

    @Test
    fun `1xx status returns NORMAL`() {
        assertEquals(WebSocketClose.NORMAL, HttpStatus.Continue.bestWebSocketCloseCode)
        assertEquals(WebSocketClose.NORMAL, HttpStatus.SwitchingProtocols.bestWebSocketCloseCode)
    }

    @Test
    fun `2xx status returns NORMAL`() {
        assertEquals(WebSocketClose.NORMAL, HttpStatus.OK.bestWebSocketCloseCode)
        assertEquals(WebSocketClose.NORMAL, HttpStatus.Created.bestWebSocketCloseCode)
        assertEquals(WebSocketClose.NORMAL, HttpStatus.NoContent.bestWebSocketCloseCode)
    }

    @Test
    fun `3xx status returns NORMAL`() {
        assertEquals(WebSocketClose.NORMAL, HttpStatus.MovedPermanently.bestWebSocketCloseCode)
        assertEquals(WebSocketClose.NORMAL, HttpStatus.Found.bestWebSocketCloseCode)
        assertEquals(WebSocketClose.NORMAL, HttpStatus.TemporaryRedirect.bestWebSocketCloseCode)
    }

    @Test
    fun `4xx status returns VIOLATED_POLICY`() {
        assertEquals(WebSocketClose.VIOLATED_POLICY, HttpStatus.BadRequest.bestWebSocketCloseCode)
        assertEquals(WebSocketClose.VIOLATED_POLICY, HttpStatus.Unauthorized.bestWebSocketCloseCode)
        assertEquals(WebSocketClose.VIOLATED_POLICY, HttpStatus.Forbidden.bestWebSocketCloseCode)
        assertEquals(WebSocketClose.VIOLATED_POLICY, HttpStatus.NotFound.bestWebSocketCloseCode)
    }

    @Test
    fun `5xx status returns INTERNAL_ERROR`() {
        assertEquals(WebSocketClose.INTERNAL_ERROR, HttpStatus.InternalServerError.bestWebSocketCloseCode)
        assertEquals(WebSocketClose.INTERNAL_ERROR, HttpStatus.BadGateway.bestWebSocketCloseCode)
        assertEquals(WebSocketClose.INTERNAL_ERROR, HttpStatus.ServiceUnavailable.bestWebSocketCloseCode)
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
        assertEquals(WebSocketClose.GOING_AWAY, CancellationException("shutting down").webSocketCloseReason)
    }

    @Test
    fun `a cancellation subclass is still GOING_AWAY`() {
        // Coroutine cancellation arrives as subclasses (e.g. JobCancellationException), never as the
        // base type, so an equality check on the class would miss every real case.
        class Nested(message: String) : CancellationException(message)
        assertEquals(WebSocketClose.GOING_AWAY, Nested("child cancelled").webSocketCloseReason)
    }

    @Test
    fun `an HttpStatusException keeps its status mapping`() {
        assertEquals(WebSocketClose.VIOLATED_POLICY, HttpStatusException(HttpStatus.Forbidden).webSocketCloseReason)
        assertEquals(WebSocketClose.INTERNAL_ERROR, HttpStatusException(HttpStatus.BadGateway).webSocketCloseReason)
    }

    @Test
    fun `an ordinary failure is still INTERNAL_ERROR`() {
        assertEquals(WebSocketClose.INTERNAL_ERROR, RuntimeException("boom").webSocketCloseReason)
    }
}
