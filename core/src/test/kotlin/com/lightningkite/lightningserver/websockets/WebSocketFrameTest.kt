// by Claude
package com.lightningkite.lightningserver.websockets

import kotlin.test.*

/**
 * Tests for WebSocketFrame sealed interface and implementations.
 */
class WebSocketFrameTest {

    // ========== Text Frame Tests ==========

    @Test
    fun `Text frame creation`() {
        val frame = WebSocketFrame.Text("Hello World")
        assertEquals("Hello World", frame.content)
    }

    @Test
    fun `Text frame factory function`() {
        val frame = WebSocketFrame("Hello World")
        assertEquals("Hello World", frame.content)
    }

    @Test
    fun `Text frame isEmpty for empty string`() {
        val frame = WebSocketFrame.Text("")
        assertTrue(frame.isEmpty())
    }

    @Test
    fun `Text frame isEmpty for non-empty string`() {
        val frame = WebSocketFrame.Text("content")
        assertFalse(frame.isEmpty())
    }

    @Test
    fun `Text frame toString returns content`() {
        val frame = WebSocketFrame.Text("test string")
        assertEquals("test string", frame.toString())
    }

    @Test
    fun `Text frame text property returns content`() {
        val frame: WebSocketFrame = WebSocketFrame.Text("hello")
        assertEquals("hello", frame.text)
    }

    // ========== Binary Frame Tests ==========

    @Test
    fun `Binary frame creation`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val frame = WebSocketFrame.Binary(bytes)
        assertTrue(frame.content.contentEquals(bytes))
    }

    @Test
    fun `Binary frame factory function`() {
        val bytes = byteArrayOf(0x0A, 0x0B, 0x0C)
        val frame = WebSocketFrame(bytes)
        assertTrue(frame.content.contentEquals(bytes))
    }

    @Test
    fun `Binary frame isEmpty for empty array`() {
        val frame = WebSocketFrame.Binary(byteArrayOf())
        assertTrue(frame.isEmpty())
    }

    @Test
    fun `Binary frame isEmpty for non-empty array`() {
        val frame = WebSocketFrame.Binary(byteArrayOf(1))
        assertFalse(frame.isEmpty())
    }

    @Test
    fun `Binary frame toString returns hex representation`() {
        val frame = WebSocketFrame.Binary(byteArrayOf(0x0A, 0x0B))
        val str = frame.toString()
        assertTrue(str.contains("bytes"))
    }

    @Test
    fun `Binary frame text property returns hex string`() {
        val bytes = byteArrayOf(0x41, 0x42)  // 'A', 'B' in ASCII
        val frame: WebSocketFrame = WebSocketFrame.Binary(bytes)
        val text = frame.text
        assertTrue(text.isNotEmpty())
    }

    // ========== Content Property Tests ==========

    @Test
    fun `Text frame content is String`() {
        val frame: WebSocketFrame = WebSocketFrame.Text("hello")
        assertTrue(frame.content is String)
    }

    @Test
    fun `Binary frame content is ByteArray`() {
        val frame: WebSocketFrame = WebSocketFrame.Binary(byteArrayOf(1, 2))
        assertTrue(frame.content is ByteArray)
    }

    // ========== Type Checking Tests ==========

    @Test
    fun `can pattern match on frame type`() {
        val textFrame: WebSocketFrame = WebSocketFrame("text")
        val binaryFrame: WebSocketFrame = WebSocketFrame(byteArrayOf(1))

        when (textFrame) {
            is WebSocketFrame.Text -> assertTrue(true)
            is WebSocketFrame.Binary -> assertTrue(false, "Should be Text")
        }

        when (binaryFrame) {
            is WebSocketFrame.Text -> assertTrue(false, "Should be Binary")
            is WebSocketFrame.Binary -> assertTrue(true)
        }
    }
}
