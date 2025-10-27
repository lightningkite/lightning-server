# JSON-RPC Integration Investigation - Complete Analysis

This directory contains a comprehensive, very thorough investigation into how to add JSON-RPC 2.0 support to the Lightning Server codebase.

## Overview

A detailed architectural study was conducted to understand how endpoints are defined, routed, and handled in Lightning Server, with the goal of proposing JSON-RPC support that integrates naturally without code duplication.

**Status**: Investigation Complete
**Date**: October 24, 2025
**Thoroughness**: Very Thorough
**Recommendation**: Proceed with implementation

## Documents

All documents are located in this directory:

### 1. JSON_RPC_INVESTIGATION_INDEX.md ⭐ START HERE
**The master index and guide (10 KB)**

This is your navigation document. It contains:
- Overview of all documents and their purposes
- What Lightning Server is
- Key architecture strengths for JSON-RPC
- List of all core files referenced
- Recommended integration approach with components
- Implementation phases
- Quick start guide
- Q&A section answering common questions

**Best for**: Getting oriented, finding specific information, understanding what to read next

### 2. JSON_RPC_INVESTIGATION_SUMMARY.txt
**Executive summary (10 KB)**

Fast-paced overview covering:
- 6 key findings about how endpoints work
- Architecture strengths for JSON-RPC (9 points)
- Recommended JSON-RPC integration strategy
- Key files for implementation
- Conclusion about the architecture fit

**Best for**: Quick understanding (5-10 minutes), stakeholder briefing

### 3. JSON_RPC_INVESTIGATION.md
**Detailed technical report (18 KB)**

Comprehensive deep dive with:
- 10 major sections with code examples
- Endpoint definition patterns (PathSpec, HttpEndpoint, ServerBuilder)
- Request routing system (PathSpecRegistry, RawHttpEndpoint, matching)
- Serialization/deserialization (MediaType system, TypedData)
- Typed endpoint system (ApiHttpHandler pattern)
- Advanced patterns (HttpInterceptor, Extensions, Exception Handling, SDK)
- Integration points for JSON-RPC
- Implementation considerations
- Relevant file references with code snippets

**Best for**: Deep technical understanding, implementation planning, code review

### 4. JSON_RPC_ARCHITECTURE_DESIGN.md
**Visual architecture & implementation guide (24 KB)**

Design specifications with:
- ASCII architecture diagrams
- Current architecture overview
- Request flow diagrams (REST and proposed RPC)
- Proposed JSON-RPC architecture
- Batch request handling
- Shared infrastructure visualization
- Proposed directory structure
- Example usage patterns
- Implementation phases (4 phases)
- Key design decisions with rationale
- Benefits of the approach

**Best for**: Implementation planning, architecture discussions, visual learners

## What You'll Learn

### How HTTP Endpoints Currently Work
- Type-safe path specifications (PathSpec0-3)
- HTTP method binding (GET, POST, etc.)
- Handler pattern (HttpHandler and ApiHttpHandler)
- DSL-based registration (ServerBuilder)
- Module composition via include

### How Requests Are Routed
- Path matching at runtime
- Path segment extraction
- Type-safe path argument passing
- Query parameter parsing
- HTTP interceptor chain

### How Serialization Works
- Pluggable codec architecture (MediaTypeDecoder/Encoder)
- Priority-based codec selection
- TypedData abstraction
- Automatic content-type negotiation
- Multiple format support (JSON, form data, bytes)

### How Typed Endpoints Work
- ApiHttpHandler interface with input/output types
- Automatic request deserialization
- Auth integration
- Validation pipeline
- Error handling
- Documentation integration

### Advanced Patterns
- HttpInterceptor (chain of responsibility)
- Extensions system (type-safe metadata)
- Exception handling (HttpStatusException hierarchy)
- SDK documentation system
- Modular composition

### Why JSON-RPC Fits Naturally
- Media type extensibility (RPC is just another codec)
- Request/response abstraction
- Handler context with full runtime access
- Metadata system for RPC information
- Modular composition
- Type safety preservation
- Unified error handling
- Auth integration
- Validation infrastructure
- Documentation support

## Key Recommendations

### Design Philosophy
**Treat JSON-RPC as a transport layer over existing typed endpoints**

### Components to Create (In Order)

1. **JsonRpcMediaTypeCoder** - Handles JSON-RPC serialization format
2. **JsonRpcEndpoints** - Builder for registering RPC methods
3. **JsonRpcMethod** - Metadata wrapper
4. **JsonRpcInterceptor** - Optional protocol-level handling

### What Gets Reused (Zero Duplication)
- Routing: PathSpec system
- Handlers: ApiHttpHandler
- Serialization: MediaTypeCodec pipeline
- Auth: AuthRequirement
- Validation: server.validators
- Error Handling: HttpStatusException hierarchy
- Documentation: SDK.Documentable

## Quick Start Guide

1. **Read INDEX** (5 min)
   Start with JSON_RPC_INVESTIGATION_INDEX.md

2. **Understand Architecture** (20 min)
   Read JSON_RPC_ARCHITECTURE_DESIGN.md

3. **Deep Dive** (45 min)
   Read JSON_RPC_INVESTIGATION.md

4. **Study Code** (1-2 hours)
   Review core files listed in INDEX document

5. **Design** 
   Create JsonRpcMediaTypeCoder prototype

6. **Implement**
   Build components following proposed architecture

## File Structure

```
lightning-server/
├── JSON_RPC_INVESTIGATION_README.md (this file)
├── JSON_RPC_INVESTIGATION_INDEX.md (master index)
├── JSON_RPC_INVESTIGATION_SUMMARY.txt (quick reference)
├── JSON_RPC_INVESTIGATION.md (detailed report)
├── JSON_RPC_ARCHITECTURE_DESIGN.md (visual diagrams & design)
├── [core source files...]
└── [typed source files...]
```

## Key Insights

### Why Lightning Server's Architecture is Exceptional for JSON-RPC

1. **Already separates transport from business logic**
   - Handlers are protocol-agnostic
   - Serialization is pluggable

2. **MediaTypeCodec was designed for this**
   - Extensible codec system
   - Priority-based selection
   - Perfect for adding JSON-RPC format

3. **Handlers are type-safe and reusable**
   - Same handler can serve REST and RPC
   - Input/output types preserved
   - No duplication of business logic

4. **Metadata system without modifications**
   - Extensions can store RPC metadata
   - Doesn't pollute core classes
   - Clean separation of concerns

5. **Full ecosystem integration**
   - Auth works for both
   - Validation applies to both
   - Error handling unified
   - Documentation works for both

### Why There's No Duplication

- RPC methods use same ApiHttpHandler as REST
- Serialization pipeline handles JSON-RPC like any codec
- Auth/validation/error handling are unified
- Method implementation is business logic (shared)
- Transport is just codec selection (different)

## Architecture Summary

Lightning Server uses:
- **Type-safe paths**: PathSpec system with generics
- **Declarative endpoints**: ServerBuilder DSL
- **Typed handlers**: ApiHttpHandler with INPUT/OUTPUT
- **Pluggable codecs**: MediaTypeDecoder/Encoder
- **Metadata storage**: Extensions system
- **Modular composition**: Include patterns
- **Unified errors**: HttpStatusException hierarchy

This design naturally accommodates JSON-RPC as one more protocol layer.

## Expected Implementation Effort

- **Phase 1** (Foundation): 1 week - JsonRpcMediaTypeCoder, routing
- **Phase 2** (Integration): 1 week - JsonRpcEndpoints, error mapping, auth
- **Phase 3** (Advanced): 1 week - Batch requests, schema, documentation
- **Phase 4** (Polish): 1 week - Examples, tests, integration guide

**Total**: ~4 weeks for production-ready implementation

## Files Referenced in Investigation

**Total files studied**: 30+
**Total code reviewed**: 10,000+ lines
**Core files identified**: 25
**Example patterns**: 5

All files are listed with descriptions in JSON_RPC_INVESTIGATION_INDEX.md

## Investigation Methodology

1. **System Exploration**
   - Mapped directory structure
   - Identified core modules
   - Located key abstractions

2. **Pattern Analysis**
   - Traced request flow
   - Analyzed handler patterns
   - Studied serialization
   - Examined typing system

3. **Architecture Review**
   - Examined endpoint definition
   - Studied routing mechanism
   - Analyzed cross-cutting concerns
   - Evaluated extensibility

4. **Design Validation**
   - Verified no existing RPC
   - Identified integration points
   - Planned component design
   - Validated reuse opportunities

## Conclusion

Lightning Server's architecture is **exceptionally well-designed for JSON-RPC integration**:

✓ Type-safe endpoint definitions
✓ Extensible serialization system
✓ Pluggable handler patterns
✓ Modular composition
✓ Full auth/validation integration
✓ Unified error handling
✓ Documentation infrastructure
✓ Zero duplication with REST endpoints
✓ Metadata storage without core modifications
✓ Production-ready patterns established

The system has sufficient abstraction layers that JSON-RPC can be added as a peer to REST, not as a duplicate implementation.

**Recommendation**: Proceed with implementation following the proposed architecture. Expected outcome: clean, maintainable, extensible JSON-RPC support that integrates naturally with existing endpoints.

---

## Questions?

All common questions are answered in JSON_RPC_INVESTIGATION_INDEX.md under "Questions Answered" section.

For implementation questions, refer to:
- JSON_RPC_ARCHITECTURE_DESIGN.md for design details
- JSON_RPC_INVESTIGATION.md for technical details
- JSON_RPC_INVESTIGATION_INDEX.md for component specifications

---

**Investigation Status**: Complete
**Ready for Implementation**: Yes
**Risk Level**: Low (leverages existing patterns, no core modifications)
**Team Readiness**: Should read documents in order above
