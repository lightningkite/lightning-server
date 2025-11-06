# com.lightningkite.lightningserver

Core shared types for Lightning Server multiplatform applications.

## Files

### LSError.kt
Contains fundamental data structures for error handling and WebSocket multiplexing:

- **LSError** - Standardized error response format used across all Lightning Server endpoints. Provides consistent structure for HTTP status codes, error details, human-readable messages, and optional stack traces.

- **MultiplexMessage** - Message format for multiplexed WebSocket connections, allowing multiple logical channels over a single WebSocket. Supports channel lifecycle management (start, data, error, end).

### HttpMethod.kt
Type-safe HTTP method representation:

- **HttpMethod** - Zero-overhead value class for HTTP methods (GET, POST, PUT, PATCH, DELETE, OPTIONS, HEAD, WEBSOCKET). Provides compile-time safety while maintaining performance through the `@JvmInline` annotation.

## Module Purpose

This module contains types that are shared between client and server code. Being a Kotlin Multiplatform module, these types work across:
- JVM (server-side)
- JavaScript (web clients)
- iOS (native clients)
- Android (native clients)
- Other Kotlin Multiplatform targets

All types are serializable using KotlinX Serialization for seamless client-server communication.
