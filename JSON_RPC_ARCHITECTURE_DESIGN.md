# JSON-RPC Integration Architecture Design

## Current Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    Lightning Server                         │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ServerBuilder (DSL)                                        │
│  ├── Register endpoints: path.get bind handler             │
│  ├── Register handlers by PathSpec + HttpMethod            │
│  └── Compose modules via include                           │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  PathSpec System (Type-Safe Routing)                        │
│  ├── PathSpec0: /path/to/endpoint                          │
│  ├── PathSpec1<A>: /path/to/{id}                           │
│  ├── PathSpec2<A,B>: /path/{id}/{name}                     │
│  └── PathSpec3<A,B,C>: /path/{id}/{name}/{action}          │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  HttpHandler Pattern                                        │
│  ├── Raw: HttpHandler<PathSpec>                            │
│  │   └── handle(request) -> response                        │
│  │                                                          │
│  └── Typed: ApiHttpHandler<PathSpec, USER, IN, OUT>        │
│      ├── inputType: KSerializer<IN>                        │
│      ├── outputType: KSerializer<OUT>                      │
│      ├── auth: AuthRequirement<USER>                       │
│      ├── successCode: HttpStatus                           │
│      └── handle(access, input) -> output                   │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Serialization System                                       │
│  ├── MediaTypeDecoder: TypedData -> <T>                    │
│  ├── MediaTypeEncoder: <T> -> TypedData                    │
│  ├── MediaTypeCoder: Both encode & decode                  │
│  ├── TypedData: data(Text|Bytes) + mediaType               │
│  └── Registry: Pluggable codecs with priority              │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Cross-Cutting Concerns                                     │
│  ├── HttpInterceptor: Chain of responsibility              │
│  ├── Extensions: Type-safe metadata storage                │
│  ├── Exception Handling: HttpStatusException -> LSError    │
│  └── Auth: AuthRequirement integration                     │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

## Request Flow (Current REST)

```
HTTP Request
    ↓
RawHttpEndpoint.match(pathSegments, method)
    ↓
PathSpecMap.Match<HttpHandler>
    ├─ Matches path segments against PathSpecs
    └─ Extracts typed path arguments
    ↓
HttpInterceptors.intercept(request)
    ├─ Can modify request
    └─ Chain of responsibility
    ↓
HttpHandler.handle(request)
    ├─ Raw: Generic handling
    └─ Typed (ApiHttpHandler):
       ├─ Parse input from request
       │  └─ GET: queryParameters()
       │  └─ POST: body.parse()
       ├─ Validate input
       ├─ Check auth
       ├─ Call business logic
       └─ Return typed output
    ↓
MediaTypeEncoder(accept header)
    ├─ Selects encoder by priority
    └─ Serializes output -> TypedData
    ↓
HttpResponse (status, body, headers)
    ↓
Client
```

## Proposed JSON-RPC Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│              JSON-RPC Layer (NEW)                                │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  HTTP POST /rpc                                                  │
│  Content-Type: application/json-rpc                              │
│  Body: {"jsonrpc": "2.0", "method": "...", "params": {}, "id":1}│
│                                                                  │
│  ↓ RPC MediaTypeCoder (new)                                     │
│                                                                  │
│  - Decode JSON-RPC request format                               │
│  - Extract: method name, params, id, version                    │
│  - Handle batch requests (array)                                │
│  - Create RpcRequest<IN> object                                 │
│                                                                  │
├──────────────────────────────────────────────────────────────────┤
│              RPC Method Routing (NEW)                            │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  JsonRpcEndpoints (extends ServerBuilder)                        │
│  ├─ Registers methods with names                                │
│  ├─ Maintains method registry: name -> ApiHttpHandler           │
│  ├─ Handles method lookup by name                               │
│  └─ Routes to appropriate handler                               │
│                                                                  │
│  Example:                                                       │
│    path.path("rpc").post bind rpcHandler {                      │
│        route("models.list", modelListHandler)                   │
│        route("models.detail", modelDetailHandler)               │
│    }                                                            │
│                                                                  │
├──────────────────────────────────────────────────────────────────┤
│              Handler Execution (REUSED)                          │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ApiHttpHandler<PathSpec0, USER, IN, OUT>                       │
│  ├─ input: Deserialized from RPC params                         │
│  ├─ auth: AuthRequirement enforced                              │
│  ├─ validators: Applied automatically                           │
│  └─ Returns: Typed output                                       │
│                                                                  │
│  NO CHANGE - Same handler as REST endpoints!                    │
│                                                                  │
├──────────────────────────────────────────────────────────────────┤
│              Response Encoding (REUSED)                          │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  MediaTypeEncoder (existing)                                    │
│  ├─ Encodes output via RPC MediaTypeCoder                       │
│  ├─ Wraps in RpcResponse format                                 │
│  │  {"jsonrpc": "2.0", "result": {...}, "id": 1}               │
│  └─ Returns TypedData                                           │
│                                                                  │
├──────────────────────────────────────────────────────────────────┤
│              Error Handling (REUSED)                             │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  HttpStatusException -> JSON-RPC Error                           │
│  ├─ BadRequestException(400) -> error(-32602)                   │
│  ├─ UnauthorizedException(401) -> error(-32001)                 │
│  ├─ ForbiddenException(403) -> error(-32002)                    │
│  ├─ NotFoundException(404) -> error(-32603)                      │
│  └─ Other exceptions -> error(-32000)                           │
│                                                                  │
│  Response format:                                               │
│  {"jsonrpc": "2.0", "error": {                                  │
│      "code": -32600,                                            │
│      "message": "Invalid Request",                              │
│      "data": {...}                                              │
│  }, "id": 1}                                                    │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

## JSON-RPC Request Flow

```
┌─────────────────────────────────────────────┐
│ Raw HTTP Request                            │
│ Content-Type: application/json-rpc          │
│ Body: JSON-RPC 2.0 request                  │
└────────────┬────────────────────────────────┘
             ↓
     ┌───────────────┐
     │ RpcMediaType  │
     │ Decoder       │
     └───────┬───────┘
             ↓
  ┌──────────────────────┐
  │ Decoded RPC Request  │
  │ - method: String     │
  │ - params: Object     │
  │ - id: Number         │
  │ - jsonrpc: "2.0"     │
  └──────────┬───────────┘
             ↓
  ┌──────────────────────────────┐
  │ JsonRpcEndpoints Router       │
  │ - Look up method by name      │
  │ - Find ApiHttpHandler         │
  └──────────┬───────────────────┘
             ↓
  ┌──────────────────────────────┐
  │ ApiHttpHandler Execution      │
  │ - Deserialize params          │
  │ - Validate parameters         │
  │ - Check auth                  │
  │ - Execute business logic      │
  │ - Return typed output         │
  └──────────┬───────────────────┘
             ↓
  ┌──────────────────────────────┐
  │ Success Response              │
  │ - result: Output object       │
  │ - id: Original ID             │
  │ - jsonrpc: "2.0"              │
  └──────────┬───────────────────┘
             ↓
  ┌──────────────────────────────┐
  │ RpcMediaTypeEncoder           │
  │ - Wrap in JSON-RPC format     │
  │ - Serialize to JSON           │
  │ - Return TypedData            │
  └──────────┬───────────────────┘
             ↓
  ┌──────────────────────────────┐
  │ HTTP Response                 │
  │ Content-Type: application/json│
  │ Status: 200 OK                │
  │ Body: JSON-RPC response       │
  └──────────────────────────────┘


Error Path (shown separately):

  ┌─────────────────────────────┐
  │ Exception Thrown             │
  │ (e.g., BadRequestException)  │
  └────────────┬────────────────┘
               ↓
  ┌─────────────────────────────┐
  │ DefaultExceptionHttpHandler  │
  │ - Convert to LSError         │
  └────────────┬────────────────┘
               ↓
  ┌──────────────────────────────┐
  │ RpcMediaTypeEncoder          │
  │ - Map to JSON-RPC error code │
  │ - Wrap in error format       │
  │ - Preserve error details     │
  └────────────┬─────────────────┘
               ↓
  ┌──────────────────────────────┐
  │ HTTP Response                │
  │ Status: 200 OK (per spec)    │
  │ Body: {"jsonrpc":"2.0",      │
  │        "error":{...},        │
  │        "id": 1}              │
  └──────────────────────────────┘
```

## Batch Request Handling

```
HTTP POST /rpc
Content-Type: application/json-rpc
Body: [
  {"jsonrpc": "2.0", "method": "models.list", "params": {}, "id": 1},
  {"jsonrpc": "2.0", "method": "models.detail", "params": {"id": 5}, "id": 2},
  {"jsonrpc": "2.0", "method": "invalid", "params": {}, "id": 3}
]

Process:
1. RpcMediaTypeDecoder detects array
2. Create array of RpcRequest objects
3. For each request:
   ├─ Route to handler
   ├─ Execute
   ├─ Collect response (success or error)
   └─ Track by ID
4. Encode all responses as array
5. Return single HTTP 200 with array of responses

Response:
[
  {"jsonrpc": "2.0", "result": [{...}], "id": 1},
  {"jsonrpc": "2.0", "result": {...}, "id": 2},
  {"jsonrpc": "2.0", "error": {"code": -32601, "message": "Method not found"}, "id": 3}
]
```

## Shared Infrastructure (No Duplication)

```
┌────────────────────────────────────────────┐
│  ApiHttpHandler<PathSpec, USER, IN, OUT>   │
│                                            │
│  - inputType: KSerializer<IN>              │
│  - outputType: KSerializer<OUT>            │
│  - auth: AuthRequirement<USER>             │
│  - handle(): Typed execution               │
│                                            │
│  USED BY:                                  │
│  ├─ REST endpoints: /api/models            │
│  ├─ RPC methods: models.list               │
│  └─ Both share same input validation       │
│     and business logic                     │
└────────────────────────────────────────────┘

┌────────────────────────────────────────────┐
│  MediaTypeCodec System                     │
│                                            │
│  - MediaTypeEncoder: <T> -> TypedData      │
│  - MediaTypeDecoder: TypedData -> <T>      │
│  - Priority-based selection                │
│                                            │
│  CODECS:                                   │
│  ├─ JSON (application/json)                │
│  ├─ Form Data (application/x-www-form)    │
│  ├─ Bytes (application/octet-stream)       │
│  └─ JSON-RPC (application/json-rpc) NEW   │
└────────────────────────────────────────────┘

┌────────────────────────────────────────────┐
│  Exception Handling                        │
│                                            │
│  HttpStatusException hierarchy:            │
│  ├─ BadRequestException                    │
│  ├─ UnauthorizedException                  │
│  ├─ ForbiddenException                     │
│  ├─ NotFoundException                      │
│  └─ Custom exceptions                      │
│                                            │
│  MAPPED TO:                                │
│  ├─ REST: HTTP status codes                │
│  ├─ RPC: JSON-RPC error codes              │
│  └─ Both use DefaultExceptionHttpHandler   │
└────────────────────────────────────────────┘

┌────────────────────────────────────────────┐
│  AuthRequirement System                    │
│                                            │
│  - Enforced in ApiHttpHandler              │
│  - Access context: HttpAccess<P, USER>     │
│  - Used by both REST and RPC               │
│  - Same permission checks                  │
└────────────────────────────────────────────┘
```

## Directory Structure (Proposed)

```
lightning-server/
├── core/
│   └── src/main/kotlin/com/lightningkite/lightningserver/
│       ├── existing code...
│       └── rpc/ (NEW)
│           ├── JsonRpcMediaTypeCoder.kt
│           ├── JsonRpcInterceptor.kt (optional)
│           └── RpcExceptions.kt (if needed)
│
├── typed/
│   └── src/main/kotlin/com/lightningkite/lightningserver/typed/
│       ├── existing code...
│       └── rpc/ (NEW)
│           ├── JsonRpcMethod.kt
│           ├── JsonRpcEndpoints.kt
│           ├── RpcDocumentation.kt (optional)
│           └── RpcTypes.kt
│
├── typed-shared/
│   └── src/commonMain/kotlin/com/lightningkite/lightningserver/typed/
│       └── rpc/ (NEW - if shared with clients)
│           └── RpcRequest.kt, RpcResponse.kt
│
└── demo/
    └── src/main/kotlin/com/lightningkite/lightningserver/demo/
        └── RpcServerExample.kt (NEW)
```

## Example Usage

```kotlin
// Define a handler (same as REST)
val listHandler = ApiHttpHandler(
    summary = "List Models",
    description = "Get all models",
    auth = userAuth,
    inputType = Query.serializer(),
    outputType = ListSerializer(Model.serializer())
) { input: Query<Model> ->
    database.query(input)
}

// Register both REST and RPC
object Server : ServerBuilder() {
    // REST endpoint
    val rest = path.path("api").path("models").get bind listHandler
    
    // RPC method (NEW)
    val rpc = path.path("rpc").post include JsonRpcEndpoints {
        method("models.list", listHandler)
        method("models.detail", detailHandler)
        method("models.create", createHandler)
    }
}

// Client usage

// REST
GET /api/models?limit=10
Accept: application/json

// RPC
POST /rpc
Content-Type: application/json-rpc

{
  "jsonrpc": "2.0",
  "method": "models.list",
  "params": {"limit": 10},
  "id": 1
}
```

## Implementation Phases

### Phase 1: Foundation
- JsonRpcMediaTypeCoder (decode/encode JSON-RPC format)
- JsonRpcMethod wrapper
- Route registration mechanism
- Basic request/response handling

### Phase 2: Integration
- JsonRpcEndpoints builder
- Error mapping (HttpStatusException -> RPC errors)
- Auth integration
- Validation reuse

### Phase 3: Advanced Features
- Batch request handling
- Streaming responses (if needed)
- OpenRPC schema generation
- Client SDK generation

### Phase 4: Documentation & Examples
- RPC documentation
- Examples in demo
- Integration guide

## Key Design Decisions

1. **Single Endpoint Pattern**: All RPC calls go to /rpc (or configurable)
   - Simplifies routing
   - Single interceptor point
   - Standard for JSON-RPC

2. **Reuse ApiHttpHandler**: Don't create RpcHandler
   - Same input/output types
   - Same auth enforcement
   - Same validation pipeline
   - Unified documentation

3. **MediaTypeCoder for Protocol**: Not separate HTTP handler
   - Treats RPC as serialization format
   - Leverages existing codec pipeline
   - Consistent with media type architecture

4. **Extensions for Metadata**: Store RPC info in handler extensions
   - No core modifications
   - Separate concerns
   - Can be removed/replaced

5. **HTTP 200 for All**: Per JSON-RPC spec
   - Errors in body, not HTTP status
   - ErrorException handlers need awareness
   - Keeps protocol clean

## Benefits of This Approach

✓ Zero duplication of business logic
✓ Same auth/validation for both REST and RPC
✓ Type safety preserved
✓ Can use same handlers for both protocols
✓ Extensible for other protocols in future
✓ Follows existing Lightning Server patterns
✓ Metadata system keeps it clean
✓ Composable via ServerBuilder
✓ Works with existing interceptors
✓ Documentation integration straightforward
