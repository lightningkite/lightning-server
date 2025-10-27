# JSON-RPC Investigation - Complete Index

This directory contains a comprehensive investigation of how to add JSON-RPC support to the Lightning Server codebase without duplicating functionality.

## Documents

### 1. JSON_RPC_INVESTIGATION_SUMMARY.txt
**Quick Reference Guide** - Read this first!
- 2-page executive summary of all findings
- Key findings organized by topic
- Architecture strengths for JSON-RPC
- Recommended integration strategy
- Key files and conclusion
- **Start here** for a quick understanding

### 2. JSON_RPC_INVESTIGATION.md
**Detailed Technical Report** - Comprehensive deep dive
- 10 major sections with code examples
- Full architectural analysis
- Implementation patterns with code snippets
- Design patterns and their purposes
- Integration points with reasoning
- Implementation considerations
- References to all relevant files
- **Read after the summary** for detailed understanding

### 3. JSON_RPC_ARCHITECTURE_DESIGN.md
**Visual Architecture & Implementation Guide** - Design specifications
- ASCII diagrams of current architecture
- Request flow diagrams
- Proposed JSON-RPC flow
- Component interaction diagrams
- Batch request handling
- Shared infrastructure visualization
- Proposed directory structure
- Example usage patterns
- Implementation phases
- Design decisions with rationale
- **Use for implementation planning**

## Investigation Findings Summary

### What is Lightning Server?

A sophisticated, type-safe Kotlin web framework with:
- Declarative endpoint definition via ServerBuilder DSL
- Type-safe path routing (PathSpec system)
- Pluggable serialization codecs (MediaTypeDecoder/Encoder)
- Typed handler pattern (ApiHttpHandler)
- Modular composition via include patterns
- Advanced features: interceptors, extensions, validation

### Key Architecture Strengths for JSON-RPC

1. **Media Type Extensibility** - JSON-RPC is just another codec
2. **Type Safety** - Full compile-time type information
3. **Reusable Handlers** - ApiHttpHandler works for REST and RPC
4. **Error Handling** - Unified exception->response pipeline
5. **Auth Integration** - Same access control for both
6. **Metadata System** - Extensions keep RPC clean
7. **Validation** - Shared validation infrastructure
8. **Documentation** - SDK system works for RPC too
9. **Interceptors** - Can add protocol-level concerns
10. **Modular Design** - RPC as optional module

### No Existing RPC Implementation

- Zero JSON-RPC code found in codebase
- Blank slate for optimal design
- Full opportunity to design cleanly

## Core Files Referenced

### Essential for Understanding

**Current Endpoint System:**
- `/core/src/main/kotlin/com/lightningkite/lightningserver/definition/endpoints.kt` - Endpoint registry
- `/core/src/main/kotlin/com/lightningkite/lightningserver/definition/builder/ServerBuilder.kt` - DSL builder
- `/core/src/main/kotlin/com/lightningkite/lightningserver/http/HttpEndpoint.kt` - Endpoint definition
- `/core/src/main/kotlin/com/lightningkite/lightningserver/http/HttpHandler.kt` - Handler interface

**Routing & Pathing:**
- `/core/src/main/kotlin/com/lightningkite/lightningserver/pathing/PathSpec.kt` - Type-safe paths
- `/core/src/main/kotlin/com/lightningkite/lightningserver/pathing/RawHttpEndpoint.kt` - Runtime routing
- `/core/src/main/kotlin/com/lightningkite/lightningserver/pathing/PathSpecRegistry.kt` - Registration
- `/core/src/main/kotlin/com/lightningkite/lightningserver/http/parse.kt` - Path/query parsing

**Serialization:**
- `/core/src/main/kotlin/com/lightningkite/lightningserver/serialization/MediaTypeCoder.kt` - Codec interfaces
- `/core/src/main/kotlin/com/lightningkite/lightningserver/serialization/Serialization.kt` - Format management

**Typed Endpoints:**
- `/typed/src/main/kotlin/com/lightningkite/lightningserver/typed/ApiHttpHandler.kt` - Typed handler
- `/typed/src/main/kotlin/com/lightningkite/lightningserver/typed/ApiHttpHandler.ext.kt` - Typed handler extensions
- `/typed/src/main/kotlin/com/lightningkite/lightningserver/typed/Access.kt` - Auth access pattern
- `/typed/src/main/kotlin/com/lightningkite/lightningserver/typed/ModelRestEndpoints.kt` - Real example

**Cross-Cutting:**
- `/core/src/main/kotlin/com/lightningkite/lightningserver/http/HttpInterceptor.kt` - Interceptor pattern
- `/core/src/main/kotlin/com/lightningkite/lightningserver/definition/Extensions.kt` - Metadata system
- `/core/src/main/kotlin/com/lightningkite/lightningserver/exceptions.kt` - Exception hierarchy
- `/core/src/main/kotlin/com/lightningkite/lightningserver/http/DefaultExceptionHttpHandler.kt` - Error handling

**Documentation:**
- `/typed/src/main/kotlin/com/lightningkite/lightningserver/typed/sdk/SDK.kt` - SDK system
- `/typed/src/main/kotlin/com/lightningkite/lightningserver/typed/ApiDocs.kt` - Documentation

### Examples to Study

- `/typed/src/main/kotlin/com/lightningkite/lightningserver/typed/ModelRestEndpoints.kt` - Complete CRUD endpoints
- `/core/src/main/kotlin/com/lightningkite/lightningserver/cors/CorsInterceptor.kt` - Interceptor example
- `/demo/src/main/kotlin/com/lightningkite/lightningserver/demo/TestModelEndpoints.kt` - Module composition

## Recommended Integration Approach

### Design Philosophy
**Treat JSON-RPC as a transport layer over existing typed endpoints**

### Components to Create

1. **JsonRpcMediaTypeCoder** (Core)
   - Implements MediaTypeDecoder/Encoder
   - Decodes application/json-rpc requests
   - Encodes JSON-RPC 2.0 responses
   - Handles batch requests
   - Maps to: `/typed/src/main/kotlin/com/lightningkite/lightningserver/rpc/JsonRpcMediaTypeCoder.kt`

2. **JsonRpcEndpoints** (Builder)
   - Extends ServerBuilder
   - Registers methods by name
   - Routes to ApiHttpHandler instances
   - Maps to: `/typed/src/main/kotlin/com/lightningkite/lightningserver/rpc/JsonRpcEndpoints.kt`

3. **JsonRpcMethod** (Metadata)
   - Wraps ApiHttpHandler with method name
   - Stored in extensions
   - Maps to: `/typed/src/main/kotlin/com/lightningkite/lightningserver/rpc/JsonRpcMethod.kt`

4. **JsonRpcInterceptor** (Optional)
   - Protocol-level concerns
   - Maps to: `/core/src/main/kotlin/com/lightningkite/lightningserver/rpc/JsonRpcInterceptor.kt`

### What Gets Reused (No Duplication)

- **Routing**: PathSpec system (method name -> path)
- **Handlers**: ApiHttpHandler (input/output already typed)
- **Serialization**: MediaTypeCodec (new format in existing pipeline)
- **Auth**: AuthRequirement (same access control)
- **Validation**: server.validators (same infrastructure)
- **Error Handling**: HttpStatusException hierarchy (map to RPC codes)
- **Documentation**: SDK.Documentable (RPC is just another format)

## Implementation Phases

**Phase 1: Foundation** (Week 1)
- JsonRpcMediaTypeCoder
- Basic request/response handling
- Method routing

**Phase 2: Integration** (Week 2)
- JsonRpcEndpoints builder
- Error mapping
- Auth integration

**Phase 3: Advanced** (Week 3)
- Batch request handling
- Schema generation
- Documentation

**Phase 4: Polish** (Week 4)
- Examples
- Tests
- Integration guide

## Expected Outcomes

After implementation:

1. Can expose existing REST endpoints as JSON-RPC methods
2. Same business logic serves both protocols
3. Single source of truth for handlers
4. No code duplication across protocols
5. Full type safety maintained
6. Auth/validation/docs unified
7. Can be used alongside REST
8. Follows JSON-RPC 2.0 spec
9. Supports batch requests
10. Extensible for future protocols

## Quick Start for Implementation

1. **Read**: JSON_RPC_INVESTIGATION_SUMMARY.txt (5 min)
2. **Understand**: JSON_RPC_ARCHITECTURE_DESIGN.md (20 min)
3. **Deep Dive**: JSON_RPC_INVESTIGATION.md (45 min)
4. **Study Code**: Review core files listed above (1-2 hours)
5. **Design**: Create JsonRpcMediaTypeCoder prototype
6. **Implement**: Build out components following recommended architecture

## Key Insights

### Why JSON-RPC Fits Naturally

Lightning Server's architecture is **exceptionally well-suited** for JSON-RPC because:

- It already separates transport (HTTP, serialization) from business logic
- MediaTypeCodec system was designed for exactly this use case
- Handlers are type-safe and reusable
- Error handling pipeline is unified
- Metadata system can store RPC info without modifications
- Modular composition allows RPC as optional feature

### Why There's No Duplication

The design avoids duplication because:

- RPC methods use same ApiHttpHandler as REST endpoints
- Serialization pipeline handles JSON-RPC format like any other
- Auth/validation/error handling are unified
- Method implementation is business logic, not transport logic
- Transport is just different codec (JSON-RPC vs JSON)

### What Makes This Clean

The proposed architecture is clean because:

- Separation of concerns: transport vs business logic
- Uses existing extension points (not modifying core)
- Follows established patterns (MediaTypeCodec, ServerBuilder)
- Composable via include pattern
- No global state or magic strings
- Type-safe from end to end

## Questions Answered

**Q: Can the same handler serve REST and RPC?**
A: Yes. ApiHttpHandler is protocol-agnostic. The transport (REST vs RPC) is just different serialization.

**Q: Where does JSON-RPC handling happen?**
A: In JsonRpcMediaTypeCoder (decode request) and JsonRpcEndpoints (route to handler).

**Q: How are errors handled?**
A: HttpStatusException hierarchy maps to JSON-RPC error codes via existing error handler.

**Q: Does it require modifying existing code?**
A: No. Uses MediaTypeCodec registration and Extensions for metadata.

**Q: Can it be optional?**
A: Yes. Include JsonRpcEndpoints only in servers that need it.

**Q: What about batch requests?**
A: JsonRpcMediaTypeCoder handles array of requests, processes each, returns array of responses.

**Q: Is type safety maintained?**
A: Yes. ApiHttpHandler preserves input/output types. KSerializer used throughout.

## Conclusion

Lightning Server's architecture is **exceptionally well-designed for JSON-RPC integration**. The investigation reveals that:

1. No duplication is necessary
2. Clean integration points exist
3. All necessary infrastructure is in place
4. Follows architectural patterns
5. Type safety can be maintained
6. Fits naturally as optional module
7. Ready for implementation

The recommended approach treats JSON-RPC as a protocol layer above the existing typed endpoint system, creating a clean, maintainable, extensible implementation.

---

**Investigation Date**: October 24, 2025
**Status**: Complete
**Recommendation**: Proceed with implementation following proposed architecture
