package com.lightningkite.lightningserver.typed.jsonrpc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * JSON-RPC 2.0 request.
 *
 * @property jsonrpc The JSON-RPC protocol version (must be "2.0")
 * @property method The name of the method to invoke
 * @property params Parameters for the method (can be null)
 * @property id Request identifier (can be null for notifications)
 */
@Serializable
public data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement? = null,
    val id: JsonElement? = null
)

/**
 * JSON-RPC 2.0 successful response.
 *
 * @property jsonrpc The JSON-RPC protocol version (must be "2.0")
 * @property result The result of the method invocation
 * @property id Request identifier (matches the request id)
 */
@Serializable
public data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val result: JsonElement,
    val id: JsonElement?
)

/**
 * JSON-RPC 2.0 error response.
 *
 * @property jsonrpc The JSON-RPC protocol version (must be "2.0")
 * @property error The error object
 * @property id Request identifier (matches the request id, or null if id couldn't be determined)
 */
@Serializable
public data class JsonRpcErrorResponse(
    val jsonrpc: String = "2.0",
    val error: JsonRpcError,
    val id: JsonElement?
)

/**
 * JSON-RPC 2.0 error object.
 *
 * @property code Error code (integer)
 * @property message Human-readable error message
 * @property data Additional error information (optional)
 */
@Serializable
public data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
) {
    public companion object {
        /** Invalid JSON was received by the server */
        public const val PARSE_ERROR: Int = -32700

        /** The JSON sent is not a valid Request object */
        public const val INVALID_REQUEST: Int = -32600

        /** The method does not exist / is not available */
        public const val METHOD_NOT_FOUND: Int = -32601

        /** Invalid method parameter(s) */
        public const val INVALID_PARAMS: Int = -32602

        /** Internal JSON-RPC error */
        public const val INTERNAL_ERROR: Int = -32603

        /** Reserved for implementation-defined server-errors (range: -32000 to -32099) */
        public const val SERVER_ERROR_MIN: Int = -32099
        public const val SERVER_ERROR_MAX: Int = -32000

        public fun parseError(message: String = "Parse error", data: JsonElement? = null): JsonRpcError =
            JsonRpcError(PARSE_ERROR, message, data)

        public fun invalidRequest(message: String = "Invalid Request", data: JsonElement? = null): JsonRpcError =
            JsonRpcError(INVALID_REQUEST, message, data)

        public fun methodNotFound(method: String): JsonRpcError =
            JsonRpcError(METHOD_NOT_FOUND, "Method not found: $method")

        public fun invalidParams(message: String = "Invalid params", data: JsonElement? = null): JsonRpcError =
            JsonRpcError(INVALID_PARAMS, message, data)

        public fun internalError(message: String = "Internal error", data: JsonElement? = null): JsonRpcError =
            JsonRpcError(INTERNAL_ERROR, message, data)
    }
}
