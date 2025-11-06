# Events Package

Server-side implementation for type-safe event handling in the notification system.

## Files

### TypedEvent.kt
Type-safe event wrappers:
- **TypedEventType**: Event type definition with model information and auto-registration
- **TypedEvent**: Type-safe event occurrence with full subject entity (not just ID)
- Conversion methods to transform between typed and untyped events

### EventHandler.kt
Event processing infrastructure:
- **EventHandler**: Interface for handling typed events
- **EventLauncher**: Provides task-based event launching with inline and asynchronous options
- DSL for defining event types with automatic endpoint creation

### EventRegistry.kt
Event type registration and querying:
- **EventRegistry**: Type-safe registry for managing event types
- **EventEndpoints**: REST API for querying registered event types with permissions

These classes work together to enable type-safe, asynchronous event processing with automatic task endpoint generation at `/events/{eventName}`.
