# Package com.lightningkite.lightningserver.typed (shared)

Client-side types and interfaces for typed Lightning Server APIs.

This package contains the cross-platform (multiplatform) definitions used by both server and client code for typed API
communication.

## Core Files

### models.kt

Data models for server functionality:

- **FunnelStart**, **FunnelInstance**, **FunnelSummary** - User funnel tracking for monitoring user flows
- **ServerHealth** - Server health status reporting with CPU and memory metrics
- **LightningServerKSchema** - Complete API schema definition for SDK generation
- **BulkRequest**, **BulkResponse** - Batch request/response types

### Fetcher.kt

- **Fetcher** - Client-side HTTP/WebSocket abstraction for making typed API calls
- Provides platform-independent interface for HTTP requests and WebSocket connections
- Supports header calculators for dynamic authentication

### ClientWebSocket.kt

- **ClientWebSocket** - Typed WebSocket client interface with reactive connection state
- Supports message handlers and connection lifecycle management

### LiveVersion.kt

- **@LiveVersion** - Annotation linking interfaces to their "live" client implementations

### ClientModelRestEndpoints.kt

Client interfaces for REST CRUD APIs:

- **ClientModelRestEndpoints** - Full REST CRUD interface for a model
- **ClientModelRestUpdatesWebsocket** - WebSocket interface for real-time model updates
- **ClientModelRestEndpointsAndUpdatesWebsocket** - Combined REST + WebSocket interface

### LiveClientModelRestEndpoints.kt

Live HTTP/WebSocket implementations of client interfaces:

- **LiveClientModelRestEndpoints** - Actual HTTP client for REST CRUD operations
- **LiveClientModelRestUpdatesWebsocket** - Actual WebSocket client for model updates
- **LiveClientModelRestEndpointsAndUpdatesWebsocket** - Combined implementation

## Usage

These types are used by generated client SDKs to provide type-safe API access. Server developers typically don't
interact with these directly, but they define the contract between server and client.

Example client usage:

```kotlin
// Create a fetcher (platform-specific implementation)
val fetcher = KtorFetcher("https://api.example.com")

// Create model client
val postsClient = LiveClientModelRestEndpoints(
    fetcher = fetcher,
    subpath = "/api/posts",
    serializer = Post.serializer(),
    idSerializer = Uuid.serializer()
)

// Use the client
val posts = postsClient.query(Query(limit = 10))
val post = postsClient.detail(someId)
```
