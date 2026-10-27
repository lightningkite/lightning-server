package com.lightningkite.lightningserver.typed.jsonrpc

import com.lightningkite.lightningserver.http.HttpStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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
    val id: JsonElement? = null,
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
    val id: JsonElement? = null,
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
    val id: JsonElement? = null,
)

/**
 * JSON-RPC 2.0 error object.
 *
 * @property code Error code
 * @property message Human-readable error message
 * @property data Additional error information (optional)
 */
@Serializable
public data class JsonRpcError(
    val code: JsonRpcErrorCode,
    val message: String,
    val data: JsonElement? = null,
) {
    public companion object {
        public fun parseError(message: String = "Parse error", data: JsonElement? = null): JsonRpcError =
            JsonRpcError(JsonRpcErrorCode.ParseError, message, data)

        public fun invalidRequest(message: String = "Invalid Request", data: JsonElement? = null): JsonRpcError =
            JsonRpcError(JsonRpcErrorCode.InvalidRequest, message, data)

        public fun methodNotFound(method: String): JsonRpcError =
            JsonRpcError(JsonRpcErrorCode.MethodNotFound, "Method not found: $method")

        public fun invalidParams(message: String = "Invalid params", data: JsonElement? = null): JsonRpcError =
            JsonRpcError(JsonRpcErrorCode.InvalidParams, message, data)

        public fun internalError(message: String = "Internal error", data: JsonElement? = null): JsonRpcError =
            JsonRpcError(JsonRpcErrorCode.InternalError, message, data)
    }
}

/**
 * A JSON-RPC 2.0 error code, serialized as the bare integer.
 *
 * Use the companion object constants for the codes JSON-RPC defines, or construct one for a code a protocol built on
 * JSON-RPC defines (MCP's `-32002` resource not found, for one).
 *
 * @property code The numeric error code
 */
@Serializable
@JvmInline
public value class JsonRpcErrorCode(public val code: Int) {
    /** Whether this is in -32099 to -32000, the range JSON-RPC reserves for implementation-defined server errors. */
    public val isServerError: Boolean get() = code in -32099..-32000

    override fun toString(): String = "$code"

    public companion object {
        /** Invalid JSON was received by the server. */
        public val ParseError: JsonRpcErrorCode = JsonRpcErrorCode(-32700)
        /** The JSON sent is not a valid Request object. */
        public val InvalidRequest: JsonRpcErrorCode = JsonRpcErrorCode(-32600)
        /** The method does not exist or is not available. */
        public val MethodNotFound: JsonRpcErrorCode = JsonRpcErrorCode(-32601)
        /** Invalid method parameters. */
        public val InvalidParams: JsonRpcErrorCode = JsonRpcErrorCode(-32602)
        /** Internal JSON-RPC error. */
        public val InternalError: JsonRpcErrorCode = JsonRpcErrorCode(-32603)
    }
}

/**
 * Thrown from a [JsonRpcMethod] or a [JsonRpcHandler] check to answer with exactly [error], at HTTP [status].
 *
 * Protocols built on JSON-RPC define their own error codes, and some (MCP, for one) their own HTTP statuses, so a method
 * that needs one throws this instead of an exception the handler would report as an internal error.
 */
public class JsonRpcException(
    public val error: JsonRpcError,
    public val status: HttpStatus = HttpStatus.OK,
) : Exception(error.message)
