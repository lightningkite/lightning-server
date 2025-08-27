package com.lightningkite.lightningserver.websockets


public sealed interface WebSocketFrame {
    public val content: Any
    public fun isEmpty(): Boolean

    @JvmInline
    public value class Text(override val content: String) : WebSocketFrame {
        override fun toString(): String = content
        override fun isEmpty(): Boolean = content.isEmpty()
    }

    @JvmInline
    public value class Binary(override val content: ByteArray) : WebSocketFrame {
        override fun toString(): String = "<bytes ${content.toHexString()}>"
        override fun isEmpty(): Boolean = content.isEmpty()
    }
}

public fun WebSocketFrame(text: String): WebSocketFrame.Text = WebSocketFrame.Text(text)
public fun WebSocketFrame(bytes: ByteArray): WebSocketFrame.Binary = WebSocketFrame.Binary(bytes)

public val WebSocketFrame.text: String
    get() = when (this) {
        is WebSocketFrame.Binary -> content.toHexString()
        is WebSocketFrame.Text -> content
    }