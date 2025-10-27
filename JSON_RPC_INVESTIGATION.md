# Lightning Server JSON-RPC Integration Investigation Report

## Executive Summary

Lightning Server uses a sophisticated type-safe, declarative endpoint definition system built on top of Kotlin. It provides excellent architectural foundations for adding JSON-RPC support without duplication. The existing patterns are highly modular and composable, making JSON-RPC a natural extension of the current system.

---

## 1. HTTP Endpoint Definition Patterns

### Current System Architecture

The endpoint system is built on several key abstractions:

#### 1.1 Core Abstractions

**PathSpec System** (`/core/src/main/kotlin/com/lightningkite/lightningserver/pathing/PathSpec.kt`)
- Type-safe path specifications with wildcard support
- Supports 0-3 path arguments with generics (PathSpec0, PathSpec1, PathSpec2, PathSpec3, PathSpecMany)
- Path segments can be constants or typed wildcards with serializers
- Supports trailing segments for file uploads/downloads
- Each path spec knows its wild cards and their types

```kotlin
// Example from code
val detailPath: PathSpec1<ID> = path.arg(Segment.Wildcard("id", info.idSerializer))
```

**HttpEndpoint** (`/core/src/main/kotlin/com/lightningkite/lightningserver/http/HttpEndpoint.kt`)
- Simple data class pairing a PathSpec with an HttpMethod
- Provides convenience properties: `.get`, `.post`, `.put`, `.patch`, `.delete`, `.options`, `.head`
- Extensible with custom HTTP methods

```kotlin
public data class HttpEndpoint<Path : PathSpec>(public val path: Path, public val method: HttpMethod)
```

#### 1.2 Builder Pattern

**ServerBuilder** (`/core/src/main/kotlin/com/lightningkite/lightningserver/definition/builder/ServerBuilder.kt`)
- DSL-based builder using `@LightningServerDsl` marker
- Register endpoints using infix `bind` operator
- Registers handlers by path + HTTP method
- Supports modular composition via `include`
- Maintains internal registries:
  - `httpHandlers: PathSpecRegistry<MapRegistry<HttpMethod, HttpHandler<*>>>`
  - `websocketHandlers: PathSpecRegistry<WebSocketHandler<*, *>>`
  - `settings: ListRegistry<ServerSetting<*, *>>`
  - `tasks, schedules, startupTasks`
  - `mediaTypeDecoders, mediaTypeEncoders`
  - `httpInterceptors, websocketInterceptors`

```kotlin
// Example from test code
object TestModelEndpoints: ServerBuilder() {
    val rest = path.path("rest") include ModelRestEndpoints(info)
}
```

#### 1.3 Handler System

**HttpHandler Interface** (`/core/src/main/kotlin/com/lightningkite/lightningserver/http/HttpHandler.kt`)
- Suspend function design with ServerRuntime context
- Timeout support (default 30 seconds)
- Takes typed HttpRequest, returns HttpResponse

```kotlin
public interface HttpHandler<PATH : PathSpec> {
    public val timeout: Duration get() = 30.seconds
    context(server: ServerRuntime)
    public suspend fun handle(request: HttpRequest<PATH>): HttpResponse
}
```

**HttpRequest** (`/core/src/main/kotlin/com/lightningkite/lightningserver/http/HttpRequest.kt`)
- Serializable request container
- Holds: path, query parameters, headers, domain, protocol, source IP, body cache
- Typed to the PathSpec it matches
- Body is TypedData (can be binary or text with media type)

**HttpResponse** (`/core/src/main/kotlin/com/lightningkite/lightningserver/http/HttpResponse.kt`)
- Simple data class with body, status, headers
- Body is TypedData (supports multiple content types)
- Status defaults based on body presence

---

## 2. Request Routing System

### Path Matching and Resolution

**PathSpecRegistry** (`/core/src/main/kotlin/com/lightningkite/lightningserver/pathing/PathSpecRegistry.kt`)
- Registry that maps PathSpec to handlers
- Prevents duplicate registrations
- Used during build phase

**RawHttpEndpoint** (`/core/src/main/kotlin/com/lightningkite/lightningserver/pathing/RawHttpEndpoint.kt`)
- Represents runtime HTTP endpoints with actual path segments
- Lazy-loads handler matches from PathSpecMap
- Supports caching of matched handlers
- Can resolve to ResolvedPath with typed arguments

**PathSegments and QueryParameters** (`/core/src/main/kotlin/com/lightningkite/lightningserver/http/parse.kt`)
- PathSegments: URL-encoded path segments (List-like)
- QueryParameters: key-value pairs with URL encoding
- Both provide parse() and toString() methods
- Support in PathAndParams for combined parsing

```kotlin
public data class PathAndParams(
    val pathSegments: PathSegments,
    val queryParameters: QueryParameters
)
```

### Runtime Matching

In RawHttpEndpoint:
```kotlin
context(server: ServerRuntime)
public val match: PathSpecMap.Match<HttpHandler<*>> get() {
    if (this.matchIfPresent == null) {
        this.matchIfPresent = server.server.endpoints.match(
            server.externalSerialization.stringArrayFormat, 
            pathSegments
        ) { it.http[method] }
    }
    return this.matchIfPresent ?: throw RouteNotFoundException(this)
}
```

The system:
1. Takes path segments from HTTP request
2. Matches against PathSpecMap to find best handler
3. Handler processes request based on its PATH type
4. Extracts path arguments during matching process

---

## 3. Serialization/Deserialization System

### MediaType System

**MediaTypeDecoder/Encoder/Coder** (`/core/src/main/kotlin/com/lightningkite/lightningserver/serialization/MediaTypeCoder.kt`)
- Extensible codec system for any media type
- Priority-based selection (float priority field)
- Context receivers for ServerRuntime
- Accepts() method for conditional application
- Supports TypedData and WebSocket frames

```kotlin
public interface MediaTypeDecoder {
    public val priority: Float get() = 0f
    public val mediaType: MediaType
    context(runtime: ServerRuntime) fun accepts(parameters: Map<String, String>): Boolean = true
    context(runtime: ServerRuntime) suspend operator fun <T> invoke(
        content: TypedData, 
        serializer: DeserializationStrategy<T>
    ): T
}

public interface MediaTypeEncoder {
    public val priority: Float get() = 0f
    public val mediaType: MediaType
    context(runtime: ServerRuntime) suspend operator fun <T> invoke(
        mediaType: MediaType, 
        serializer: SerializationStrategy<T>, 
        value: T
    ): TypedData
}
```

### Request/Response Handling

**Serialization Class** (`/core/src/main/kotlin/com/lightningkite/lightningserver/serialization/Serialization.kt`)
- Manages serialization formats
- Provides JSON, StringArray, KotlinBytes, FormData formats
- Configurable via SerializersModule
- Ignores unknown keys, lenient parsing
- Separate instances for internal/external serialization

**ApiHttpHandler** (`/typed/src/main/kotlin/com/lightningkite/lightningserver/typed/ApiHttpHandler.kt`)
- Extended HttpHandler with typed input/output
- Handles conversion from HttpMethod context (GET uses queryParameters, POST uses body)
- Calls validators before execution
- Automatically serializes response based on Accept header
- Success code and error cases defined

```kotlin
public interface ApiHttpHandler<PATH : PathSpec, USER : HasId<*>?, INPUT, OUTPUT> 
    : HttpHandler<PATH>, SDK.Documentable {
    override val auth: AuthRequirement<USER>
    override val inputType: KSerializer<INPUT>
    override val outputType: KSerializer<OUTPUT>
    public val successCode: HttpStatus
    public val errorCases: List<LSError>
    public val examples: List<Example<INPUT, OUTPUT>>

    context(server: ServerRuntime)
    public suspend fun handle(access: HttpAccess<PATH, USER>, input: INPUT): OUTPUT
}
```

### TypedData System

Represents content with media type:
```kotlin
public data class TypedData(
    public val data: Data,  // Text or Bytes
    public val mediaType: MediaType
)
```

---

## 4. Typed Endpoint System

### ApiHttpHandler Pattern

The typed endpoint system wraps raw HTTP handling with strong typing:

**Features:**
- Input/Output type serializers
- Auth requirement specification
- Success HTTP status code
- Error cases documentation
- Examples for documentation
- Automatic request/response serialization

**Factory Functions:**
- `explicitApiHttpHandler()` - Full explicit specification
- `ApiHttpHandler()` - Reified type version (requires type parameters)

**Implementation Pattern:**
```kotlin
val myHandler: ApiHttpHandler<PathSpec0, USER, InputType, OutputType> =
    ApiHttpHandler(
        summary = "My Endpoint",
        description = "Does something",
        auth = someAuthRequirement,
        successCode = HttpStatus.OK,
        errorCases = listOf(...),
        examples = listOf(...)
    ) { input: InputType ->
        // Handler implementation
        OutputType(...)
    }
```

### ModelRestEndpoints Example

Shows practical typed endpoint usage:
- Creates CRUD endpoints for models
- Nested endpoints at detail path with ID wildcard
- Query and bulk operations
- Permission checks
- Error handling for unique violations
- All endpoints use ApiHttpHandler pattern

---

## 5. Existing Advanced Patterns

### HttpInterceptor System

**HttpInterceptor** (`/core/src/main/kotlin/com/lightningkite/lightningserver/http/HttpInterceptor.kt`)
- Chain of responsibility pattern
- Can modify requests/responses
- Instrumented with telemetry
- Can be stacked and compiled
- Examples: CORS, authentication, logging

```kotlin
public fun interface HttpInterceptor {
    context(runtime: ServerRuntime)
    public suspend fun intercept(
        request: HttpRequest<*>, 
        cont: suspend context(ServerRuntime) (HttpRequest<*>) -> HttpResponse
    ): HttpResponse
}
```

**CorsInterceptor** (`/core/src/main/kotlin/com/lightningkite/lightningserver/cors/CorsInterceptor.kt`)
- Example interceptor for CORS
- Also implements WebSocketHandlerInterceptor
- Handles OPTIONS requests
- Checks origin matching

### Extensions System

**Extensions** (`/core/src/main/kotlin/com/lightningkite/lightningserver/definition/Extensions.kt`)
- Type-safe extension mechanism
- Read-only (Extensions) and read-write (MutableExtensions)
- Key-based access with generics
- DegradingKey for write/read type pairs
- Used for metadata attachment to endpoints

**Use in SDK System:**
```kotlin
// InterfaceInfo is stored as extension
public interface Extended {
    public val extensions: Extensions
}

public interface Extendable : Extended {
    public override val extensions: MutableExtensions
}
```

### Exception Handling

**HttpStatusException Hierarchy** (`/core/src/main/kotlin/com/lightningkite/lightningserver/exceptions.kt`)
- Base: HttpStatusException(status, detail, message, data)
- BadRequestException (400)
- UnauthorizedException (401)
- ForbiddenException (403)
- NotFoundException (404)
- RouteNotFoundException
- Custom exceptions extend this

**Exception Handler** (`/core/src/main/kotlin/com/lightningkite/lightningserver/http/DefaultExceptionHttpHandler.kt`)
- Converts exceptions to LSError responses
- Includes stack traces in debug mode
- Returns proper HTTP status codes
- Serializes errors to configured formats

**LSError Structure:**
```kotlin
@Serializable
public data class LSError(
    public val http: Int,
    public val detail: String = "",
    public val message: String = "",
    public val data: String = "",
    public val stackTrace: String = ""
)
```

### SDK/Documentation System

**SDK.Documentable Interface** (`/typed/src/main/kotlin/com/lightningkite/lightningserver/typed/sdk/SDK.kt`)
- Endpoints implement this for documentation
- Provides: summary, description, functionName, auth, inputType, outputType
- Used to generate SDK documentation and client code
- InterfaceInfo metadata stored in extensions

**SDK Data Structures:**
- SDK.Function: Documentable, Endpoint, or Websocket
- SDK.Function.Argument: Name and type
- SDK.Module: Hierarchical structure of endpoints
- SDK.Data: Complete SDK tree

---

## 6. Key Integration Points for JSON-RPC

### Strengths of Current Architecture

1. **Media Type Extensibility**: Already supports plugging in new codecs via MediaTypeDecoder/Encoder
2. **Request/Response Abstraction**: TypedData can hold any content, serialized/deserialized via codecs
3. **Handler Context Receivers**: ServerRuntime context allows accessing configuration, validators, etc.
4. **Metadata System**: Extensions allow attaching RPC metadata without modifying core classes
5. **Modular Composition**: ServerBuilder composition allows adding RPC endpoints as a module
6. **Type Safety**: Full compile-time type information available
7. **Interceptor Pattern**: Can add RPC-specific interceptors for protocol-level concerns
8. **Error Handling**: Unified exception-to-response pipeline

### Natural Extension Points

1. **MediaTypeCoder for application/json-rpc**: Decode RPC requests, encode RPC responses
2. **RpcHttpHandler extending ApiHttpHandler**: Represents RPC method with input/output
3. **RpcEndpoints extending ServerBuilder**: Groups RPC methods at /rpc path
4. **RpcInterceptor for protocol handling**: JSON-RPC specific request/response transformations
5. **RpcDocumentation extending SDK.Documentable**: Generate RPC schema/documentation
6. **Extension Keys**: Store RPC metadata (method names, versions, etc.)

### No Duplication Needed Because

1. Routing reuses PathSpec system (one endpoint per RPC method)
2. Handlers reuse ApiHttpHandler pattern (input/output types already there)
3. Serialization reuses MediaTypeCodec (RPC is just another content format)
4. Auth reuses AuthRequirement (same access control)
5. Error handling reuses HttpStatusException hierarchy (map to RPC errors)
6. Documentation reuses SDK system (RPC methods are just Documentable)

---

## 7. Recommended JSON-RPC Architecture

### High Level Design

```
HTTP POST /rpc
  Content-Type: application/json-rpc (or with MediaTypeCoder)
  
  Request: { "jsonrpc": "2.0", "method": "models.list", "params": {...}, "id": 1 }
  
  Router:
    1. RpcMediaTypeCoder decodes JSON-RPC request
    2. Extracts method name and params
    3. Routes to registered RPC method handlers
    4. Handlers are ApiHttpHandler instances (reuse pattern)
    5. Responses encoded back as JSON-RPC
    
  Error Handling:
    - Convert HttpStatusException to JSON-RPC error codes
    - Batch requests support
    - Call tracking and ID matching
```

### Key Classes to Create

1. **JsonRpcMediaTypeCoder** - Implements MediaTypeCodec
   - Decodes JSON-RPC 2.0 request format
   - Encodes JSON-RPC 2.0 response format
   - Handles batch requests

2. **JsonRpcMethod** - Wraps ApiHttpHandler
   - Associates method name with handler
   - Defines version, tags, etc.
   - Stores in extensions for metadata

3. **JsonRpcEndpoints** - Extends ServerBuilder
   - Registers methods at common /rpc endpoint
   - Handles method routing
   - Could extend to support sub-routes

4. **JsonRpcInterceptor** - Implements HttpInterceptor
   - Protocol-level concerns
   - Request validation
   - Response formatting

### Where It Fits

```
ServerBuilder
  |
  +-- Traditional HTTP routes (existing)
  |     path.get bind ApiHttpHandler(...)
  |
  +-- RPC routes (new)
        path.path("rpc").post bind RpcHandler {
            // Routes to registered methods
        }
```

Or as a module:
```
path.path("rpc") include JsonRpcEndpoints {
    method("models.list", modelListHandler)
    method("models.detail", modelDetailHandler)
}
```

---

## 8. Implementation Considerations

### Batch Request Handling
- JSON-RPC supports array of requests in single call
- Would need RpcMediaTypeCoder to handle this
- Could use HttpHandler's TypedData.sink for streaming large batches

### Method Naming Convention
- Use dot notation: "module.method"
- Could extract from ApiHttpHandler documentation or explicit registration
- Metadata stored in extensions

### Type Preservation
- Use existing ApiHttpHandler's inputType/outputType
- Serializers already available
- No new serialization infrastructure needed

### Error Mapping
- JSON-RPC error codes (-32700 to -32600 for protocol, -32000 to -32099 for server)
- Map HttpStatus/LSError to appropriate codes
- Preserve original error details in error object

### Documentation Generation
- SDK.Documentable already supports RPC methods
- Can generate JSON Schema for parameters
- Document in OpenRPC format alongside existing SDK generation

### Auth Integration
- ApiHttpHandler already requires auth specification
- Can enforce per-method or globally
- Access context already available

### Testing
- Existing HttpHandler test patterns apply
- Create test requests with JSON-RPC payload
- Verify response format

---

## 9. Files Relevant to Implementation

### Core Files to Read
- `/core/src/main/kotlin/com/lightningkite/lightningserver/definition/endpoints.kt`
- `/core/src/main/kotlin/com/lightningkite/lightningserver/definition/builder/ServerBuilder.kt`
- `/core/src/main/kotlin/com/lightningkite/lightningserver/http/HttpHandler.kt`
- `/core/src/main/kotlin/com/lightningkite/lightningserver/http/HttpInterceptor.kt`
- `/core/src/main/kotlin/com/lightningkite/lightningserver/serialization/MediaTypeCoder.kt`
- `/typed/src/main/kotlin/com/lightningkite/lightningserver/typed/ApiHttpHandler.kt`
- `/typed/src/main/kotlin/com/lightningkite/lightningserver/typed/ModelRestEndpoints.kt`

### Examples to Study
- `/typed/src/main/kotlin/com/lightningkite/lightningserver/typed/ModelRestEndpoints.kt` (typed endpoints)
- `/core/src/main/kotlin/com/lightningkite/lightningserver/cors/CorsInterceptor.kt` (interceptor)
- `/demo/src/main/kotlin/com/lightningkite/lightningserver/demo/TestModelEndpoints.kt` (module usage)

### Building Blocks
- MediaTypeCodec system for encoding/decoding
- ServerBuilder for registration
- HttpHandler/ApiHttpHandler for execution
- HttpInterceptor for cross-cutting concerns
- Extensions for metadata storage
- Exception hierarchy for error handling

---

## 10. Summary

Lightning Server's architecture is **exceptionally well-designed for JSON-RPC integration**:

- Type-safe path specifications allow routing by method name
- Pluggable MediaType system supports JSON-RPC format
- ApiHttpHandler pattern directly maps to RPC methods
- Error handling infrastructure converts to JSON-RPC errors
- Interceptor chain supports protocol-level concerns
- Extensions system stores RPC metadata without modification
- ServerBuilder composition allows RPC as a module
- SDK documentation system can generate RPC schemas
- Full auth integration via existing requirements
- Validation infrastructure applies to RPC params

**Implementation approach**: Treat JSON-RPC as a transport layer above the existing typed endpoint system, creating RpcMediaTypeCoder to handle protocol specifics while reusing all existing handler, auth, serialization, and error infrastructure. This eliminates duplication and maintains consistency across REST and RPC transports.

