package com.lightningkite.lightningserver.serialization

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.services.data.*
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.*

/**
 * Decoder for deserializing data from a specific media type (e.g., JSON, XML, form data).
 *
 * Implementations convert typed data in a particular format into Kotlin objects using
 * KotlinX Serialization strategies.
 */
public interface MediaTypeDecoder {
    /**
     * Priority for selecting this decoder when multiple decoders support the same media type.
     * Higher values indicate higher priority. Default is 0.
     */
    public val priority: Float get() = 0f

    /**
     * The media type this decoder handles (e.g., "application/json").
     */
    public val mediaType: MediaType

    /**
     * Determines if this decoder accepts the given media type parameters.
     *
     * Media type parameters are key-value pairs following the type, such as charset in
     * "application/json; charset=utf-8".
     *
     * @param parameters The media type parameters to check
     * @return true if this decoder can handle the parameters, false otherwise
     */
    public context(runtime: ServerRuntime)
    fun accepts(parameters: Map<String, String>): Boolean = true

    /**
     * Deserializes typed data into a Kotlin object.
     *
     * @param content The typed data to deserialize
     * @param serializer The deserialization strategy for type T
     * @return The deserialized object
     */
    public context(runtime: ServerRuntime)
    suspend operator fun <T> invoke(content: TypedData, serializer: DeserializationStrategy<T>): T

    /**
     * Deserializes a WebSocket frame into a Kotlin object.
     *
     * Default implementation converts the frame to [TypedData] and delegates to [invoke].
     *
     * @param content The WebSocket frame to deserialize
     * @param serializer The deserialization strategy for type T
     * @return The deserialized object
     */
    public context(runtime: ServerRuntime)
    suspend operator fun <T> invoke(content: WebSocketFrame, serializer: DeserializationStrategy<T>): T =
        invoke(
            when (content) {
                is WebSocketFrame.Binary -> TypedData(Data.Bytes(content.content), mediaType)
                is WebSocketFrame.Text -> TypedData(Data.Text(content.content), mediaType)
            }, serializer
        )
}

/**
 * Encoder for serializing Kotlin objects to a specific media type.
 *
 * Implementations convert Kotlin objects into typed data in a particular format using
 * KotlinX Serialization strategies.
 */
public interface MediaTypeEncoder {
    /**
     * Priority for selecting this encoder when multiple encoders support the same media type.
     * Higher values indicate higher priority. Default is 0.
     */
    public val priority: Float get() = 0f

    /**
     * The media type this encoder produces (e.g., "application/json").
     */
    public val mediaType: MediaType

    /**
     * Determines if this encoder accepts the given media type parameters.
     *
     * @param parameters The media type parameters to check
     * @return true if this encoder can handle the parameters, false otherwise
     */
    public context(runtime: ServerRuntime)
    fun accepts(parameters: Map<String, String>): Boolean = true

    /**
     * Serializes a Kotlin object to typed data.
     *
     * @param mediaType The target media type (may include additional parameters)
     * @param serializer The serialization strategy for type T
     * @param value The value to serialize
     * @return The serialized typed data
     */
    public context(runtime: ServerRuntime)
    suspend operator fun <T> invoke(mediaType: MediaType, serializer: SerializationStrategy<T>, value: T): TypedData

    /**
     * Serializes a Kotlin object to a WebSocket frame.
     *
     * Default implementation serializes to [TypedData] and converts to an appropriate
     * frame type (Text for text data, Binary for everything else).
     *
     * @param mediaType The target media type
     * @param serializer The serialization strategy for type T
     * @param value The value to serialize
     * @return The WebSocket frame
     */
    public context(runtime: ServerRuntime)
    suspend fun <T> ws(mediaType: MediaType, serializer: SerializationStrategy<T>, value: T): WebSocketFrame =
        invoke(mediaType, serializer, value).let {
            when (it.data) {
                is Data.Text -> WebSocketFrame.Text(it.text())
                else -> WebSocketFrame.Binary(it.data.bytes())
            }
        }

    /**
     * Serializes a Kotlin object for streaming responses.
     *
     * Default implementation delegates to [invoke]. Implementations may override this
     * to provide more efficient streaming behavior.
     *
     * @param mediaType The target media type
     * @param serializer The serialization strategy for type T
     * @param value The value to serialize
     * @return The serialized typed data suitable for streaming
     */
    public context(runtime: ServerRuntime)
    suspend fun <T> streaming(mediaType: MediaType, serializer: KSerializer<T>, value: T): TypedData =
        invoke(mediaType, serializer, value)
}

/**
 * Combined interface for both encoding and decoding a specific media type.
 *
 * Implement this interface when a single class can handle both serialization and
 * deserialization for a media type. This is the typical case for symmetric formats
 * like JSON.
 */
public interface MediaTypeCoder : MediaTypeDecoder, MediaTypeEncoder {
    override val priority: Float get() = 0f
    override context(runtime: ServerRuntime)
    fun accepts(parameters: Map<String, String>): Boolean = true
}

/*
 * TODO: API Recommendations for MediaTypeCoder.kt
 *
 * 1. The priority system allows multiple coders for the same media type, but there's no
 *    documentation on how ties are resolved when priorities are equal. Document the behavior
 *    (first registered? last registered? undefined?).
 *
 * 2. The accepts() function defaults to returning true, meaning coders accept all parameters
 *    by default. This could lead to incorrect handling of charset or other parameters.
 *    Consider requiring explicit parameter handling or at least logging when accepts() is not overridden.
 *
 * 3. MediaTypeEncoder.ws() converts Data.Text to Text frame but everything else to Binary.
 *    JSON is typically text but would be sent as Binary. Document this behavior or consider
 *    checking the media type (application/json -> Text, application/octet-stream -> Binary).
 *
 * 4. MediaTypeEncoder.streaming() has a default implementation but no guidance on when to override.
 *    Document use cases like JSON streaming, CSV generation, etc.
 *
 * 5. No error handling guidance for malformed input in invoke(). Should implementations throw
 *    specific exceptions? Return null? Document expected error handling patterns.
 *
 * 6. The MediaTypeCoder interface has duplicate default implementations of priority and accepts()
 *    due to inheriting from both interfaces. While this works, it's redundant and could be confusing.
 *
 * 7. No size limits or validation requirements documented. Implementations could accept
 *    unbounded input leading to memory issues. Add guidance on defensive parsing.
 */