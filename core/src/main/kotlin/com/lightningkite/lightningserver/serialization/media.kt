package com.lightningkite.lightningserver.serialization

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.data.AbstractSuspendingSource
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.SuspendingSource
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.request
import kotlinx.io.Buffer
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer

/**
 * Registry of media type encoders.
 *
 * Maintains a mapping from media types to their encoder implementations. Multiple encoders
 * can be registered for the same media type and are sorted by priority (highest first).
 *
 * Implements Map interface for read access while providing mutation methods for registration.
 */
public class MediaTypeEncoderRegistry(
    private val registry: HashMap<MediaType, ArrayList<MediaTypeEncoder>> = HashMap(),
) : Map<MediaType, List<MediaTypeEncoder>> by registry {
    /**
     * Registers a new encoder.
     *
     * The encoder is added to the list for its media type and the list is sorted by priority.
     *
     * @param encoder The encoder to register
     */
    public fun register(encoder: MediaTypeEncoder) {
        registry.getOrPut(encoder.mediaType, ::ArrayList).apply {
            add(encoder)
            sortByDescending { it.priority }
        }
    }

    /**
     * Includes encoders from another registry or map.
     *
     * @param encoders The encoders to include
     */
    public fun include(encoders: Map<MediaType, List<MediaTypeEncoder>>) {
        for ((type, encoders) in encoders) {
            registry.getOrPut(type, ::ArrayList).apply {
                addAll(encoders)
                sortByDescending { it.priority }
            }
        }
    }
}

/**
 * Registry of media type decoders.
 *
 * Maintains a mapping from media types to their decoder implementations. Multiple decoders
 * can be registered for the same media type and are sorted by priority (highest first).
 *
 * Implements Map interface for read access while providing mutation methods for registration.
 */
public class MediaTypeDecoderRegistry(
    private val registry: HashMap<MediaType, ArrayList<MediaTypeDecoder>> = HashMap(),
) : Map<MediaType, List<MediaTypeDecoder>> by registry {
    /**
     * Registers a new decoder.
     *
     * The decoder is added to the list for its media type and the list is sorted by priority.
     *
     * @param decoder The decoder to register
     */
    public fun register(decoder: MediaTypeDecoder) {
        registry.getOrPut(decoder.mediaType, ::ArrayList).apply {
            add(decoder)
            sortByDescending { it.priority }
        }
    }

    /**
     * Includes decoders from another registry or map.
     *
     * @param decoders The decoders to include
     */
    public fun include(decoders: Map<MediaType, List<MediaTypeDecoder>>) {
        for ((type, decoders) in decoders) {
            registry.getOrPut(type, ::ArrayList).apply {
                addAll(decoders)
                sortByDescending { it.priority }
            }
        }
    }
}

/**
 * Finds an encoder for this media type.
 *
 * Returns the highest-priority encoder that accepts this media type's parameters.
 *
 * @return The encoder, or null if none found
 */
context(serverRuntime: ServerRuntime)
public val MediaType.encoder: MediaTypeEncoder?
    get() = serverRuntime.server.mediaTypeEncoders[withoutParameters]?.firstOrNull { it.accepts(this.parameters) }

/**
 * Finds an encoder for any media type in this list.
 *
 * Returns the first media type with a matching encoder that accepts its parameters.
 *
 * @return A pair of (media type, encoder), or null if none found
 */
context(serverRuntime: ServerRuntime)
public val List<MediaType>.encoder: Pair<MediaType, MediaTypeEncoder>?
    get() = firstNotNullOfOrNull { type ->
        serverRuntime.server.mediaTypeEncoders[type.withoutParameters]
            ?.firstOrNull { it.accepts(type.parameters) }
            ?.let { type to it }
    }

/**
 * Gets the default encoder (highest priority across all media types).
 *
 * @return A pair of (media type, encoder)
 */
context(serverRuntime: ServerRuntime)
public val defaultEncoder: Pair<MediaType, MediaTypeEncoder>
    get() = serverRuntime.server.mediaTypeEncoders.values.asSequence()
        .flatten()
        .maxByOrNull { it.priority }
        ?.let { it.mediaType to it }
        ?: throw IllegalStateException("No encoders found - you need to register some media coders.  Try adding `init { registerBasicMediaTypeCoders() }` to your server builder.")

/**
 * Finds a decoder for this media type.
 *
 * Returns the highest-priority decoder that accepts this media type's parameters.
 *
 * @return The decoder, or null if none found
 */
context(serverRuntime: ServerRuntime)
public val MediaType.decoder: MediaTypeDecoder?
    get() = serverRuntime.server.mediaTypeDecoders[withoutParameters]?.firstOrNull { it.accepts(this.parameters) }

/**
 * This body, or null if there is nothing in it.
 *
 * HTTP draws no line between a request with no body and a request with an empty one, and an engine
 * cannot always tell which it has before the body is read: a chunked body, or one that ends along
 * with its HTTP/2 stream, may turn out to hold nothing. [HttpRequest.body] is null only when the
 * engine could rule a body out from the headers, so code that treats "no body" as a case of its own
 * should go through this rather than check for null alone.
 *
 * A body whose size is not known is read until its first byte arrives or it ends. The body returned
 * takes the place of this one, which must not be read afterwards.
 */
public suspend fun TypedData.takeUnlessEmpty(): TypedData? {
    data.size?.let { size ->
        if (size > 0L) return this
        close()
        return null
    }
    val source = data.suspendingSource()
    val head = Buffer()
    if (!source.request(head, 1)) return null
    return TypedData.suspendingSource(PrefixedSource(head, source), mediaType)
}

/** The bytes of [head], then whatever is left of [rest]. */
private class PrefixedSource(private val head: Buffer, private val rest: SuspendingSource) : AbstractSuspendingSource() {
    override suspend fun fill(into: Buffer): Long = if (head.exhausted()) rest.read(into) else head.transferTo(into)
    override fun release(cause: Throwable?) {
        head.clear()
        rest.cancel(cause)
    }
}

/**
 * Parses this TypedData into a Kotlin object using the appropriate decoder.
 *
 * Automatically selects the decoder based on the data's media type.
 *
 * @param serializer The deserialization strategy
 * @return The parsed object
 * @throws BadRequestException if no decoder is found or parsing fails
 */
context(serverRuntime: ServerRuntime)
public suspend fun <T> TypedData.parse(serializer: DeserializationStrategy<T>): T {
    val format = mediaType.decoder
        ?: throw BadRequestException("No media type decoder found supporting $mediaType")
    return try {
        format.decode(this, serializer)
    } catch (e: SerializationException) {
        throw BadRequestException(e.message ?: "Unknown formatting error", cause = e.cause)
    }
}

/**
 * Serializes this value to TypedData using an encoder from the accepts list.
 *
 * Selects the first matching encoder from the accepts list, or falls back to the default encoder.
 *
 * @param accepts List of acceptable media types (e.g., from Accept header)
 * @return The serialized TypedData
 */
context(serverRuntime: ServerRuntime)
public suspend inline fun <reified T> T.toTypedData(accepts: List<MediaType>): TypedData =
    toTypedData(accepts, serializerOrContextual())

/**
 * Serializes this value to TypedData using an encoder from the accepts list.
 *
 * @param accepts List of acceptable media types (e.g., from Accept header)
 * @param serializer The serialization strategy
 * @return The serialized TypedData
 */
context(serverRuntime: ServerRuntime)
public suspend fun <T> T.toTypedData(accepts: List<MediaType>, serializer: SerializationStrategy<T>): TypedData {
    val (type, format) = accepts.encoder ?: defaultEncoder
    return format.encode(type, serializer, this)
}

/**
 * Parses query parameters from this HTTP request into a Kotlin object.
 *
 * Uses FormDataFormat to deserialize URL parameters. Multiple values for the same
 * parameter are joined with commas.
 *
 * **Important gotcha:** Unit serializer is special-cased to always return Unit without
 * attempting to parse parameters.
 *
 * @param serializer The deserialization strategy
 * @return The parsed object
 * @throws BadRequestException if parsing fails
 */
context(server: ServerRuntime)
public fun <T> HttpRequest<*>.queryParameters(serializer: KSerializer<T>): T {
    try {
        @Suppress("UNCHECKED_CAST")
        if (serializer == Unit.serializer()) return Unit as T
        return server.internalSerialization.formDataFormat.decodeFromMap(
            serializer,
            queryParameters.groupBy { it.first }.mapValues { it.value.joinToString(",") { it.second } }
        )
    } catch (e: SerializationException) {
        throw BadRequestException(
            detail = "serialization",
            message = e.message ?: "Unknown serialization error",
            cause = e.cause
        )
    }
}

/*
 * TODO: Consider refactoring the encoder/decoder lookup logic to avoid repetition.
 * The pattern of finding the first acceptable encoder/decoder appears multiple times.
 * A helper function or extension could improve maintainability.
 *
 * TODO: Consider adding support for media type quality values (q-parameters) when selecting
 * from multiple acceptable media types. Currently, the first match is used, but Accept headers
 * can specify preferences via quality values.
 */