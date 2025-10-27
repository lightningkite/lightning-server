package com.lightningkite.lightningserver.rpc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * JSON-RPC 2.0 request envelope.
 * The `params` field is kept as JsonElement for lazy deserialization.
 */
@Serializable
public data class RpcRequestEnvelope(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement? = null,
    val id: JsonElement? = null
)

/**
 * JSON-RPC 2.0 success response.
 */
@Serializable
public data class RpcResponse(
    val jsonrpc: String = "2.0",
    val result: JsonElement,
    val id: JsonElement?
)

/**
 * JSON-RPC 2.0 error response.
 */
@Serializable
public data class RpcErrorResponse(
    val jsonrpc: String = "2.0",
    val error: RpcError,
    val id: JsonElement?
)

/**
 * JSON-RPC 2.0 error object.
 */
@Serializable
public data class RpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

/**
 * Standard JSON-RPC 2.0 error codes.
 */
public object RpcErrorCode {
    /** Invalid JSON was received by the server. */
    public const val PARSE_ERROR: Int = -32700

    /** The JSON sent is not a valid Request object. */
    public const val INVALID_REQUEST: Int = -32600

    /** The method does not exist / is not available. */
    public const val METHOD_NOT_FOUND: Int = -32601

    /** Invalid method parameter(s). */
    public const val INVALID_PARAMS: Int = -32602

    /** Internal JSON-RPC error. */
    public const val INTERNAL_ERROR: Int = -32603

    // Custom application errors (starting at -32000)
    /** Authentication required but not provided. */
    public const val UNAUTHORIZED: Int = -32001

    /** Authenticated but lacking permissions. */
    public const val FORBIDDEN: Int = -32002

    /** Requested resource not found. */
    public const val NOT_FOUND: Int = -32003
}

/**
 * Exception for RPC protocol errors.
 */
public class RpcException(
    public val code: Int,
    public val msg: String,
    public val requestId: JsonElement? = null,
    public val errorData: JsonElement? = null
) : Exception(msg) {
    public fun toResponse(): RpcErrorResponse = RpcErrorResponse(
        error = RpcError(code = code, message = msg, data = errorData),
        id = requestId
    )
}
