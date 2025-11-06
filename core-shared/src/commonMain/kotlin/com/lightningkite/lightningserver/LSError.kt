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
    val error: String? = null
)
