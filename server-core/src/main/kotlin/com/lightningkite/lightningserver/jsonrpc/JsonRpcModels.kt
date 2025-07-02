package com.lightningkite.lightningserver.jsonrpc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Represents a JSON-RPC request object as defined in the JSON-RPC 2.0 specification.
 * @see <a href="https://www.jsonrpc.org/specification">JSON-RPC 2.0 Specification</a>
 */
@Serializable
data class JsonRpcRequest(
    /**
     * A String specifying the version of the JSON-RPC protocol. MUST be exactly "2.0".
     */
    val jsonrpc: String = "2.0",
    
    /**
     * A String containing the name of the method to be invoked.
     */
    val method: String,
    
    /**
     * A Structured value that holds the parameter values to be used during the invocation of the method.
     * This member MAY be omitted.
     */
    val params: JsonElement? = null,
    
    /**
     * An identifier established by the Client that MUST contain a String, Number, or NULL value if included.
     * If it is not included it is assumed to be a notification.
     */
    val id: String? = null
)

/**
 * Represents a JSON-RPC response object as defined in the JSON-RPC 2.0 specification.
 * @see <a href="https://www.jsonrpc.org/specification">JSON-RPC 2.0 Specification</a>
 */
@Serializable
data class JsonRpcResponse(
    /**
     * A String specifying the version of the JSON-RPC protocol. MUST be exactly "2.0".
     */
    val jsonrpc: String = "2.0",
    
    /**
     * This member is REQUIRED on success.
     * This member MUST NOT exist if there was an error invoking the method.
     */
    val result: JsonElement? = null,
    
    /**
     * This member is REQUIRED on error.
     * This member MUST NOT exist if there was no error triggered during invocation.
     */
    val error: JsonRpcError? = null,
    
    /**
     * This member is REQUIRED.
     * It MUST be the same as the value of the id member in the Request Object.
     * If there was an error in detecting the id in the Request object (e.g. Parse error/Invalid Request), it MUST be Null.
     */
    val id: String? = null
)

/**
 * Represents a JSON-RPC error object as defined in the JSON-RPC 2.0 specification.
 * @see <a href="https://www.jsonrpc.org/specification">JSON-RPC 2.0 Specification</a>
 */
@Serializable
data class JsonRpcError(
    /**
     * A Number that indicates the error type that occurred.
     */
    val code: Int,
    
    /**
     * A String providing a short description of the error.
     */
    val message: String,
    
    /**
     * A Primitive or Structured value that contains additional information about the error.
     * This may be omitted.
     */
    val data: JsonElement? = null
) {
    companion object {
        // Pre-defined error codes as per JSON-RPC 2.0 specification
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
        
        // Error messages
        const val PARSE_ERROR_MESSAGE = "Parse error"
        const val INVALID_REQUEST_MESSAGE = "Invalid Request"
        const val METHOD_NOT_FOUND_MESSAGE = "Method not found"
        const val INVALID_PARAMS_MESSAGE = "Invalid params"
        const val INTERNAL_ERROR_MESSAGE = "Internal error"
        
        // Factory methods for standard errors
        fun parseError(data: JsonElement? = null) = JsonRpcError(PARSE_ERROR, PARSE_ERROR_MESSAGE, data)
        fun invalidRequest(data: JsonElement? = null) = JsonRpcError(INVALID_REQUEST, INVALID_REQUEST_MESSAGE, data)
        fun methodNotFound(data: JsonElement? = null) = JsonRpcError(METHOD_NOT_FOUND, METHOD_NOT_FOUND_MESSAGE, data)
        fun invalidParams(data: JsonElement? = null) = JsonRpcError(INVALID_PARAMS, INVALID_PARAMS_MESSAGE, data)
        fun internalError(data: JsonElement? = null) = JsonRpcError(INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE, data)
    }
}