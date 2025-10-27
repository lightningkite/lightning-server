# JSON-RPC Integration Proposal for Lightning Server

## Executive Summary

Add JSON-RPC 2.0 support to Lightning Server by treating it as a **protocol structure over existing JSON serialization**, with zero duplication of business logic, auth, or validation.

**Key Insight**: JSON-RPC is just a convention about JSON structure, not a different serialization format. We use standard `JsonMediaTypeCoder` and wrap/unwrap the protocol envelope.

## Design Philosophy

- **JSON-RPC is a protocol structure**, not a serialization format
- **Reuse existing ApiHttpHandlers** - same handlers serve REST and RPC
- **Standard JSON serialization** - no custom MediaTypeCoder needed
- **Zero duplication** - business logic, auth, validation all shared
- **Minimal new code** - just routing and protocol structure

## Architecture Overview

### What Gets Reused (Zero Duplication)

✓ **JSON Serialization** - Existing `JsonMediaTypeCoder`
✓ **Handlers** - Same `ApiHttpHandler<IN, OUT>` serves both protocols
✓ **Auth** - Same `AuthRequirement` and permission checks
✓ **Validation** - Same `server.validators`
✓ **Error Handling** - Same `HttpStatusException` hierarchy
✓ **Documentation** - Same `SDK.Documentable` system
✓ **Type Safety** - Same `KSerializer` pipeline

### What's New (Minimal)

1. **Protocol Data Classes** - `RpcRequest`, `RpcResponse`, `RpcError`
2. **`JsonRpcHandler`** - Routes method names to existing handlers
3. **`JsonRpcEndpoints`** - Builder DSL for method registration
4. **Error Mapping** - `HttpStatusException` → JSON-RPC error codes

## Request/Response Flow

### Request Structure

```kotlin
// Client sends standard JSON
POST /rpc
Content-Type: application/json

{
  "jsonrpc": "2.0",
  "method": "models.list",
  "params": {"limit": 10, "offset": 0},
  "id": 1
}
```

### Server Processing

```kotlin
// 1. Standard JSON deserialization (existing JsonMediaTypeCoder)
val envelope = json.decodeFromString<RpcRequestEnvelope>(requestBody)

// 2. Route to handler by method name
val handler = methodRegistry[envelope.method] // returns ApiHttpHandler
val paramsJson = envelope.params // JsonElement

// 3. Deserialize params using handler's inputType
val input = json.decodeFromJsonElement(handler.inputType, paramsJson)

// 4. Execute handler (same as REST - auth, validation, business logic)
val output = handler.handle(access, input)

// 5. Wrap in RPC response
val response = RpcResponse(
    jsonrpc = "2.0",
    result = json.encodeToJsonElement(handler.outputType, output),
    id = envelope.id
)

// 6. Standard JSON serialization
return json.encodeToString(response)
```

### Response Structure

```kotlin
// Success
{
  "jsonrpc": "2.0",
  "result": [
    {"id": 1, "name": "Model 1"},
    {"id": 2, "name": "Model 2"}
  ],
  "id": 1
}

// Error
{
  "jsonrpc": "2.0",
  "error": {
    "code": -32602,
    "message": "Invalid params",
    "data": {"field": "limit", "issue": "must be positive"}
  },
  "id": 1
}
```

## Implementation Components

### 1. Protocol Data Classes

```kotlin
// core/src/main/kotlin/com/lightningkite/lightningserver/rpc/RpcTypes.kt

package com.lightningkite.lightningserver.rpc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * JSON-RPC 2.0 request envelope.
 * The `params` field is kept as JsonElement for lazy deserialization.
 */
@Serializable
data class RpcRequestEnvelope(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement? = null,
    val id: JsonElement? = null
)

/**
 * JSON-RPC 2.0 success response.
 */
@Serializable
data class RpcResponse(
    val jsonrpc: String = "2.0",
    val result: JsonElement,
    val id: JsonElement?
)

/**
 * JSON-RPC 2.0 error response.
 */
@Serializable
data class RpcErrorResponse(
    val jsonrpc: String = "2.0",
    val error: RpcError,
    val id: JsonElement?
)

/**
 * JSON-RPC 2.0 error object.
 */
@Serializable
data class RpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

/**
 * Standard JSON-RPC error codes.
 */
object RpcErrorCode {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    // Custom application errors
    const val UNAUTHORIZED = -32001
    const val FORBIDDEN = -32002
    const val NOT_FOUND = -32003
}
```

### 2. JSON-RPC Handler

```kotlin
// core/src/main/kotlin/com/lightningkite/lightningserver/rpc/JsonRpcHandler.kt

package com.lightningkite.lightningserver.rpc

import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.exceptions.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Handles JSON-RPC 2.0 requests by routing to existing ApiHttpHandlers.
 */
class JsonRpcHandler<P>(
    private val json: Json,
    private val methodRegistry: Map<String, ApiHttpHandler<*, *, *, *>>
) : HttpHandler<P> {

    override suspend fun HttpAccess<P>.handle(input: Unit): HttpResponse {
        return try {
            val envelope = parseRequest(request.body?.text() ?: "")
            val response = processRequest(envelope)
            HttpResponse(
                status = HttpStatus.OK,
                body = TypedData.text(
                    json.encodeToString(RpcResponse.serializer(), response),
                    MediaType.Application.Json
                )
            )
        } catch (e: RpcException) {
            HttpResponse(
                status = HttpStatus.OK, // JSON-RPC always returns 200
                body = TypedData.text(
                    json.encodeToString(RpcErrorResponse.serializer(), e.toResponse()),
                    MediaType.Application.Json
                )
            )
        } catch (e: HttpStatusException) {
            HttpResponse(
                status = HttpStatus.OK,
                body = TypedData.text(
                    json.encodeToString(RpcErrorResponse.serializer(), e.toRpcError()),
                    MediaType.Application.Json
                )
            )
        }
    }

    private fun parseRequest(body: String): RpcRequestEnvelope {
        return try {
            json.decodeFromString(RpcRequestEnvelope.serializer(), body)
        } catch (e: Exception) {
            throw RpcException(RpcErrorCode.PARSE_ERROR, "Parse error", null)
        }
    }

    private suspend fun HttpAccess<P>.processRequest(
        envelope: RpcRequestEnvelope
    ): RpcResponse {
        // Find handler
        val handler = methodRegistry[envelope.method]
            ?: throw RpcException(
                RpcErrorCode.METHOD_NOT_FOUND,
                "Method not found: ${envelope.method}",
                envelope.id
            )

        // Deserialize params
        val input = try {
            if (envelope.params != null) {
                json.decodeFromJsonElement(
                    handler.inputType as DeserializationStrategy<Any?>,
                    envelope.params
                )
            } else {
                Unit // No params
            }
        } catch (e: Exception) {
            throw RpcException(
                RpcErrorCode.INVALID_PARAMS,
                "Invalid params: ${e.message}",
                envelope.id
            )
        }

        // Execute handler (cast required due to type erasure)
        @Suppress("UNCHECKED_CAST")
        val typedHandler = handler as ApiHttpHandler<P, *, Any?, Any?>
        val output = typedHandler.handleTyped(this, input)

        // Serialize result
        val resultJson = json.encodeToJsonElement(
            handler.outputType as SerializationStrategy<Any?>,
            output
        )

        return RpcResponse(
            result = resultJson,
            id = envelope.id
        )
    }
}

/**
 * Internal exception for RPC protocol errors.
 */
private class RpcException(
    val code: Int,
    val msg: String,
    val requestId: JsonElement?
) : Exception(msg) {
    fun toResponse() = RpcErrorResponse(
        error = RpcError(code = code, message = msg),
        id = requestId
    )
}

/**
 * Maps HttpStatusException to JSON-RPC error codes.
 */
private fun HttpStatusException.toRpcError(id: JsonElement? = null): RpcErrorResponse {
    val code = when (this) {
        is BadRequestException -> RpcErrorCode.INVALID_PARAMS
        is UnauthorizedException -> RpcErrorCode.UNAUTHORIZED
        is ForbiddenException -> RpcErrorCode.FORBIDDEN
        is NotFoundException -> RpcErrorCode.NOT_FOUND
        else -> RpcErrorCode.INTERNAL_ERROR
    }

    return RpcErrorResponse(
        error = RpcError(
            code = code,
            message = message ?: "Server error",
            data = detail?.let { json.encodeToJsonElement(it) }
        ),
        id = id
    )
}
```

### 3. Builder DSL

```kotlin
// typed/src/main/kotlin/com/lightningkite/lightningserver/typed/rpc/JsonRpcEndpoints.kt

package com.lightningkite.lightningserver.typed.rpc

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.rpc.JsonRpcHandler
import kotlinx.serialization.json.Json

/**
 * Builder for registering JSON-RPC methods.
 */
class JsonRpcEndpoints<P> {
    private val methods = mutableMapOf<String, ApiHttpHandler<P, *, *, *>>()

    /**
     * Register a method by name.
     */
    fun <USER, IN, OUT> method(
        name: String,
        handler: ApiHttpHandler<P, USER, IN, OUT>
    ) {
        methods[name] = handler
    }

    internal fun build(json: Json): JsonRpcHandler<P> {
        return JsonRpcHandler(json, methods.toMap())
    }
}

/**
 * Extension for ServerBuilder to add JSON-RPC endpoints.
 */
fun <P> ServerBuilder.jsonRpc(
    configure: JsonRpcEndpoints<P>.() -> Unit
): JsonRpcHandler<P> {
    val endpoints = JsonRpcEndpoints<P>()
    endpoints.configure()
    return endpoints.build(
        // Use server's JSON configuration
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            isLenient = true
        }
    )
}
```

## Usage Example

```kotlin
// Define handlers once (same as REST)
val listModelsHandler = ApiHttpHandler(
    summary = "List Models",
    description = "Get all models with pagination",
    auth = userAuth,
    inputType = QueryModel.serializer(),
    outputType = ListSerializer(Model.serializer())
) { query: QueryModel ->
    database.query(query)
}

val getModelHandler = ApiHttpHandler(
    summary = "Get Model",
    description = "Get a single model by ID",
    auth = userAuth,
    inputType = GetModelRequest.serializer(),
    outputType = Model.serializer()
) { request: GetModelRequest ->
    database.get(request.id) ?: throw NotFoundException("Model not found")
}

// Register endpoints
object MyServer : ServerBuilder() {
    // REST endpoints
    val restList = path.path("api").path("models").get bind listModelsHandler
    val restGet = path.path("api").path("models").path(intPath).get bind getModelHandler

    // JSON-RPC endpoint (same handlers!)
    val rpc = path.path("rpc").post bind jsonRpc {
        method("models.list", listModelsHandler)
        method("models.get", getModelHandler)
    }
}
```

## Client Examples

### REST Client

```http
GET /api/models?limit=10&offset=0
Accept: application/json
Authorization: Bearer token123

Response:
[
  {"id": 1, "name": "Model 1"},
  {"id": 2, "name": "Model 2"}
]
```

### JSON-RPC Client

```http
POST /rpc
Content-Type: application/json
Authorization: Bearer token123

{
  "jsonrpc": "2.0",
  "method": "models.list",
  "params": {"limit": 10, "offset": 0},
  "id": 1
}

Response:
{
  "jsonrpc": "2.0",
  "result": [
    {"id": 1, "name": "Model 1"},
    {"id": 2, "name": "Model 2"}
  ],
  "id": 1
}
```

### Batch Request Support

```http
POST /rpc
Content-Type: application/json

[
  {"jsonrpc": "2.0", "method": "models.list", "params": {"limit": 5}, "id": 1},
  {"jsonrpc": "2.0", "method": "models.get", "params": {"id": 42}, "id": 2}
]

Response:
[
  {"jsonrpc": "2.0", "result": [...], "id": 1},
  {"jsonrpc": "2.0", "result": {...}, "id": 2}
]
```

## Error Handling

### Mapping HTTP Exceptions to RPC Errors

| Exception | HTTP Status | RPC Error Code | RPC Error Name |
|-----------|-------------|----------------|----------------|
| `BadRequestException` | 400 | -32602 | Invalid params |
| `UnauthorizedException` | 401 | -32001 | Unauthorized |
| `ForbiddenException` | 403 | -32002 | Forbidden |
| `NotFoundException` | 404 | -32003 | Not found |
| Other | 500 | -32603 | Internal error |

### Error Response Example

```kotlin
// Handler throws exception
throw BadRequestException(
    detail = "validation",
    message = "Limit must be positive"
)

// Automatic conversion to RPC error
{
  "jsonrpc": "2.0",
  "error": {
    "code": -32602,
    "message": "Limit must be positive",
    "data": "validation"
  },
  "id": 1
}
```

## Implementation Plan

### Phase 1: Core Implementation (1-2 days)

**Files to create:**
- `core/src/main/kotlin/com/lightningkite/lightningserver/rpc/RpcTypes.kt`
- `core/src/main/kotlin/com/lightningkite/lightningserver/rpc/JsonRpcHandler.kt`
- `typed/src/main/kotlin/com/lightningkite/lightningserver/typed/rpc/JsonRpcEndpoints.kt`

**Tasks:**
1. Define protocol data classes (RpcRequest, RpcResponse, RpcError)
2. Implement JsonRpcHandler routing logic
3. Implement error mapping
4. Create builder DSL

**Testing:**
- Unit tests for request/response parsing
- Unit tests for error code mapping
- Integration test with simple handler

### Phase 2: Advanced Features (1-2 days)

**Tasks:**
1. Batch request support (handle array of requests)
2. Notification support (requests without `id`)
3. Enhanced error details (include stack traces in debug mode)
4. Performance optimization (handler lookup caching)

**Testing:**
- Batch request tests
- Notification tests
- Error detail tests

### Phase 3: Documentation & Examples (1 day)

**Tasks:**
1. Add demo server example showing both REST and RPC
2. Document usage in README
3. Create migration guide for exposing existing endpoints via RPC
4. Optional: OpenRPC schema generation for documentation

**Files:**
- `demo/src/main/kotlin/com/lightningkite/lightningserver/demo/RpcExample.kt`
- Update main documentation

## File Structure

```
lightning-server/
├── core/src/main/kotlin/com/lightningkite/lightningserver/
│   └── rpc/
│       ├── RpcTypes.kt          # Protocol data classes
│       └── JsonRpcHandler.kt    # Request routing & handling
│
├── typed/src/main/kotlin/com/lightningkite/lightningserver/typed/
│   └── rpc/
│       └── JsonRpcEndpoints.kt  # Builder DSL
│
└── demo/src/main/kotlin/com/lightningkite/lightningserver/demo/
    └── RpcExample.kt            # Usage examples
```

## Key Benefits

### For Developers

✓ **Zero duplication** - Write handler once, expose as REST and/or RPC
✓ **Type safety** - Full compile-time type checking preserved
✓ **Familiar patterns** - Uses existing `ApiHttpHandler` and `ServerBuilder`
✓ **Unified auth** - Same permission system for both protocols
✓ **Consistent validation** - Same validators apply to both
✓ **Single source of truth** - Business logic in one place

### For Clients

✓ **Flexible access** - Choose REST or RPC based on needs
✓ **Batch operations** - Multiple calls in single request
✓ **Standard protocol** - JSON-RPC 2.0 spec compliance
✓ **Clear errors** - Structured error responses with codes

### For Maintenance

✓ **Minimal new code** - ~200 lines for core functionality
✓ **No core modifications** - Uses existing extension points
✓ **Composable** - RPC is optional, can be added/removed easily
✓ **Testable** - Standard testing patterns apply

## Comparison to Original Proposal

| Aspect | Original | Revised |
|--------|----------|---------|
| Custom MediaTypeCoder | Yes | **No** - Reuses JSON |
| Lines of code | ~400 | **~200** |
| Serialization | Custom | **Standard JSON** |
| Complexity | Medium | **Low** |
| Flexibility | Good | **Better** (standard JSON) |

## Open Questions

1. **Batch request priority?** Should this be in Phase 1 or Phase 2?
2. **Method naming convention?** Enforce dot notation (e.g., "module.method")?
3. **Schema generation?** Should we auto-generate OpenRPC schema from handlers?
4. **WebSocket support?** JSON-RPC over WebSocket for pub/sub?

## Conclusion

This design provides JSON-RPC support with:
- **Minimal new code** (~200 lines)
- **Zero duplication** of business logic
- **Standard JSON serialization** (no custom codec)
- **Full integration** with existing auth, validation, and error handling
- **Type safety** throughout

The implementation is straightforward and follows Lightning Server's architectural patterns. Ready to proceed with Phase 1?
