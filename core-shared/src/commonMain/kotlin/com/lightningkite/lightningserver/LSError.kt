package com.lightningkite.lightningserver

import kotlinx.serialization.Serializable

/**
 * Represents a standardized error response from Lightning Server endpoints.
 *
 * This class provides a consistent error format across all API responses, enabling
 * both client and server to communicate errors in a predictable manner.
 *
 * @property http The HTTP status code (e.g., 404, 500, 400)
 * @property detail A short, machine-readable error code or slug (e.g., "not-found", "validation-failed")
 * @property message A human-readable error message for display to end users
 * @property data Additional context or structured error data as a JSON string
 * @property stackTrace Optional stack trace for debugging (typically only included in development/debug modes)
 *
 * Example usage:
 * ```kotlin
 * LSError(
 *     http = 404,
 *     detail = "not-found",
 *     message = "The requested resource was not found"
 * )
 * ```
 */
@Serializable
public data class LSError(
    val http: Int,
    val detail: String = "",
    val message: String = "",
    val data: String = "",
    val stackTrace: String? = null,
)

/**
 * Represents a message in a multiplexed WebSocket connection.
 *
 * Lightning Server supports multiplexing multiple logical channels over a single WebSocket connection.
 * Each message is tagged with a channel identifier to route it to the appropriate handler.
 *
 * @property channel The logical channel identifier for routing this message
 * @property path Optional HTTP path for initial channel setup (used when [start] is true)
 * @property queryParams Optional query parameters for initial channel setup (used when [start] is true)
 * @property start Indicates this message initiates a new channel stream
 * @property end Indicates this message terminates the channel stream
 * @property data Optional message payload as a string (typically JSON)
 * @property error Optional error message if the channel encountered an error
 *
 * Gotcha: Only one of [data] or [error] should be set at a time. If [error] is present,
 * it indicates a failure state for the channel.
 */
@Serializable
public data class MultiplexMessage(
    val channel: String,
    val path: String? = null,
    val queryParams: Map<String, List<String>>? = null,
    val start: Boolean = false,
    val end: Boolean = false,
    val data: String? = null,
    val error: String? = null,
)

/*
 * TODO: API Recommendations
 *
 * 1. LSError: Consider adding factory methods for common error types:
 *    - LSError.notFound(message: String, detail: String = "not-found")
 *    - LSError.badRequest(message: String, detail: String = "bad-request")
 *    - LSError.unauthorized(message: String, detail: String = "unauthorized")
 *    This would make error creation more consistent and less error-prone.
 *
 * 2. LSError: The 'data' field is a String but typically contains JSON. Consider:
 *    - Making it more type-safe with a generic parameter or JsonElement type
 *    - Adding a helper method: inline fun <reified T> dataAs(): T to deserialize
 *    - Documenting that it should be valid JSON
 *
 * 3. LSError: Consider adding validation that 'http' is a valid HTTP status code (100-599)
 *
 * 4. MultiplexMessage: The mutual exclusivity of 'data' and 'error' is mentioned in docs but
 *    not enforced. Consider:
 *    - Using a sealed interface with DataMessage and ErrorMessage subclasses
 *    - Adding an init block that validates only one is set
 *    - Using a when-exhaustive pattern helper
 *
 * 5. MultiplexMessage: Consider adding validation that 'path' and 'queryParams' are only
 *    present when 'start' is true (or document if other combinations are valid)
 *
 * 6. Both classes: Consider adding convenience methods for common patterns:
 *    - LSError.isClientError: Boolean (http in 400..499)
 *    - LSError.isServerError: Boolean (http in 500..599)
 *    - MultiplexMessage.isControl: Boolean (start || end)
 */
