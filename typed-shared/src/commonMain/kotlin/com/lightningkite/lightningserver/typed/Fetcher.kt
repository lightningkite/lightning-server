package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.HttpMethod
import kotlinx.serialization.KSerializer

/**
 * Client-side interface for making HTTP requests and WebSocket connections to a Lightning Server API.
 *
 * This abstraction allows different implementations (e.g., platform-specific HTTP clients) while
 * providing type-safe serialization and deserialization.
 *
 * Implementations should handle:
 * - HTTP request/response serialization based on content negotiation
 * - WebSocket connection management
 * - URL encoding of path parameters
 * - Header management including custom authentication headers
 */
public interface Fetcher {
    /**
     * Creates a new Fetcher with additional headers computed dynamically for each request.
     *
     * Headers are calculated just before each request, allowing for fresh authentication tokens.
     *
     * @param calculator Suspend function that returns header key-value pairs
     * @return A new Fetcher instance with the added header calculator
     */
    public fun withHeaderCalculator(calculator: suspend () -> List<Pair<String, String>>): Fetcher

    /**
     * Executes an HTTP request with typed serialization.
     *
     * Serializes the input body, sends the request, and deserializes the response.
     *
     * @param I Input type to serialize as request body
     * @param O Output type to deserialize from response body
     * @param url Complete URL for the request
     * @param method HTTP method to use
     * @param inSerializer Serializer for the request body
     * @param body Request body to serialize
     * @param outSerializer Serializer for the response body
     * @return Deserialized response body
     * @throws Exception if the request fails or response cannot be deserialized
     */
    public suspend operator fun <I, O> invoke(
        url: String,
        method: HttpMethod,
        inSerializer: KSerializer<I>,
        body: I,
        outSerializer: KSerializer<O>,
    ): O

    /**
     * Creates a typed WebSocket connection.
     *
     * The connection is not opened until [ClientWebSocket.connect] is called.
     *
     * @param I Type for messages sent to the server
     * @param O Type for messages received from the server
     * @param url WebSocket URL to connect to
     * @param inSerializer Serializer for outgoing messages
     * @param outSerializer Serializer for incoming messages
     * @return A WebSocket client configured for this connection
     */
    public fun <I, O> websocket(
        url: String,
        inSerializer: KSerializer<I>,
        outSerializer: KSerializer<O>,
    ): ClientWebSocket<I, O>

    /**
     * Encodes a value to a URL-safe string representation for use in path parameters.
     *
     * This is typically used for encoding IDs and other path parameters.
     *
     * @param T Type of value to encode
     * @param value Value to encode
     * @param serializer Serializer for the value
     * @return URL-encoded string representation
     */
    public fun <T> url(value: T, serializer: KSerializer<T>): String
}

/*
 * TODO: API Improvements
 *
 * 1. Consider adding request/response interceptors for logging, retry logic, and custom error handling
 * 2. Add timeout configuration support at the Fetcher level
 * 3. Consider providing a builder pattern for Fetcher configuration (headers, timeouts, base URL, etc.)
 * 4. Add support for request cancellation (returning Job or providing CancellationToken)
 * 5. Consider adding built-in retry logic with exponential backoff for failed requests
 * 6. The url() method could benefit from a URLEncoder abstraction for platform-specific encoding
 */
