// by Claude
package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.http.HttpStatus
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

    // ========== bestWebsocketCloseCode Tests ==========

    @Test
    fun `1xx status returns NORMAL`() {
        assertEquals(WebSocketClose.NORMAL, HttpStatus.Continue.bestWebsocketCloseCode)
        assertEquals(WebSocketClose.NORMAL, HttpStatus.SwitchingProtocols.bestWebsocketCloseCode)
    }

    @Test
    fun `2xx status returns NORMAL`() {
        assertEquals(WebSocketClose.NORMAL, HttpStatus.OK.bestWebsocketCloseCode)
        assertEquals(WebSocketClose.NORMAL, HttpStatus.Created.bestWebsocketCloseCode)
        assertEquals(WebSocketClose.NORMAL, HttpStatus.NoContent.bestWebsocketCloseCode)
    }

    @Test
    fun `3xx status returns NORMAL`() {
        assertEquals(WebSocketClose.NORMAL, HttpStatus.MovedPermanently.bestWebsocketCloseCode)
        assertEquals(WebSocketClose.NORMAL, HttpStatus.Found.bestWebsocketCloseCode)
        assertEquals(WebSocketClose.NORMAL, HttpStatus.TemporaryRedirect.bestWebsocketCloseCode)
    }

    @Test
    fun `4xx status returns VIOLATED_POLICY`() {
        assertEquals(WebSocketClose.VIOLATED_POLICY, HttpStatus.BadRequest.bestWebsocketCloseCode)
        assertEquals(WebSocketClose.VIOLATED_POLICY, HttpStatus.Unauthorized.bestWebsocketCloseCode)
        assertEquals(WebSocketClose.VIOLATED_POLICY, HttpStatus.Forbidden.bestWebsocketCloseCode)
        assertEquals(WebSocketClose.VIOLATED_POLICY, HttpStatus.NotFound.bestWebsocketCloseCode)
    }

    @Test
    fun `5xx status returns INTERNAL_ERROR`() {
        assertEquals(WebSocketClose.INTERNAL_ERROR, HttpStatus.InternalServerError.bestWebsocketCloseCode)
        assertEquals(WebSocketClose.INTERNAL_ERROR, HttpStatus.BadGateway.bestWebsocketCloseCode)
        assertEquals(WebSocketClose.INTERNAL_ERROR, HttpStatus.ServiceUnavailable.bestWebsocketCloseCode)
    }
}
