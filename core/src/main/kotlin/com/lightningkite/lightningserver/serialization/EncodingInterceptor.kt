package com.lightningkite.lightningserver.serialization

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.instrument
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy

/**
 * Sees every value the server encodes, with its serializer, just before it is encoded.
 *
 * Install one on a `ServerBuilder`. It is called for everything encoded through the server's media
 * type encoders: HTTP responses from any handler, WebSocket frames, and error bodies, including values
 * that are encoded but never sent. Output that skips those encoders, such as bytes a handler writes
 * itself, is not seen.
 *
 * Do not alter the value. Throw to stop the encoding, and with it the send. Expect many calls per
 * execution, such as one per WebSocket frame.
 */
public interface EncodingInterceptor {
    /** The name of this interceptor, used for instrumentation and debugging. */
    public val name: String get() = this::class.simpleName ?: "anonymous"

    /**
     * Called just before [value] is encoded with [serializer].
     *
     * Walk [serializer] rather than reflecting on [value] to see exactly what will be encoded, and use
     * [ServerRuntime.execution] to tell which execution it belongs to.
     */
    context(runtime: ServerRuntime)
    public suspend fun <T> beforeEncode(serializer: SerializationStrategy<T>, value: T)
}

/**
 * Calls every installed [EncodingInterceptor] with a value about to be encoded.
 *
 * The server's encoders already do this, so call it only for output encoded some other way. An
 * interceptor's exception propagates to the caller.
 */
public suspend fun <T> ServerRuntime.notifyEncoding(serializer: SerializationStrategy<T>, value: T) {
    for (interceptor in server.encodingInterceptors) {
        instrument(interceptor.name) { interceptor.beforeEncode(serializer, value) }
    }
}

/** An encoder that calls the [EncodingInterceptor]s before delegating to [wrapped]. */
internal class ObservedEncoder(private val wrapped: MediaTypeEncoder) : MediaTypeEncoder by wrapped {
    context(runtime: ServerRuntime)
    override suspend fun <T> encode(mediaType: MediaType, serializer: SerializationStrategy<T>, value: T): TypedData {
        runtime.notifyEncoding(serializer, value)
        return wrapped.encode(mediaType, serializer, value)
    }

    context(runtime: ServerRuntime)
    override suspend fun <T> ws(mediaType: MediaType, serializer: SerializationStrategy<T>, value: T): WebSocketFrame {
        runtime.notifyEncoding(serializer, value)
        return wrapped.ws(mediaType, serializer, value)
    }

    context(runtime: ServerRuntime)
    override suspend fun <T> streaming(mediaType: MediaType, serializer: KSerializer<T>, value: T): TypedData {
        runtime.notifyEncoding(serializer, value)
        return wrapped.streaming(mediaType, serializer, value)
    }
}
