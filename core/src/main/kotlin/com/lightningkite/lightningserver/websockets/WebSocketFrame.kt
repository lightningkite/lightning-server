package com.lightningkite.lightningserver.websockets

/**
 * Represents a frame of data transmitted over a WebSocket connection.
 *
 * WebSocket frames can be either text (UTF-8 strings) or binary (raw bytes).
 * This sealed interface provides type-safe access to both frame types.
 *
 * @property content The frame's data (String for Text, ByteArray for Binary)
 */
public sealed interface WebSocketFrame {
    public val content: Any

    /** Returns true if the frame contains no data. */
    public fun isEmpty(): Boolean

    /**
     * A text WebSocket frame containing a UTF-8 string.
     *
     * Uses @JvmInline for zero-overhead string wrapping.
     */
    @JvmInline
    public value class Text(override val content: String) : WebSocketFrame {
        override fun toString(): String = content
        override fun isEmpty(): Boolean = content.isEmpty()
    }

    /**
     * A binary WebSocket frame containing raw bytes.
     *
     * Uses @JvmInline for zero-overhead byte array wrapping.
     */
    @JvmInline
    public value class Binary(override val content: ByteArray) : WebSocketFrame {
        override fun toString(): String = "<bytes ${content.toHexString()}>"
        override fun isEmpty(): Boolean = content.isEmpty()
    }
}

/** Factory function to create a text WebSocket frame. */
public fun WebSocketFrame(text: String): WebSocketFrame.Text = WebSocketFrame.Text(text)

/** Factory function to create a binary WebSocket frame. */

public fun WebSocketFrame(bytes: ByteArray): WebSocketFrame.Binary = WebSocketFrame.Binary(bytes)

/**
 * Convenience property to get frame content as a string.
 *
 * For text frames, returns the string content directly.
 * For binary frames, returns a hex string representation.
 */
public val WebSocketFrame.text: String
    get() = when (this) {
        is WebSocketFrame.Binary -> content.toHexString()
        is WebSocketFrame.Text -> content
    }