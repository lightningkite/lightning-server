# Events Package

Server-side implementation for type-safe event handling in the notification system.

## Files

### Event.kt

- **EventDefinition**: A type-safe event type, pairing an `EventType` with the model information for its subject
- **Event**: A type-safe event occurrence carrying the full subject entity, not just its ID. Converts to an untyped
  `EventData` for storage

### EventHandler.kt

- **EventHandler**: Interface for handling events. `NotificationEndpoints` implements it
- **EventLauncher**: Launches one event type, either through its task (`launcher(subject)`) or inline
  (`handleInline(subject)`)
- **`EventHandler.event(name, info) { ... }`**: Defines an event type, registers it, and returns its `EventLauncher`. The
  setup block is where content generators and subscribers are attached

### EventRegistry.kt

- **EventRegistry**: The registry of every event definition on the server

### EventEndpoints.kt

- **EventEndpoints**: REST API for querying registered event types with permissions

Each event's task is mounted at `/events/{event-name}`, so launching an event is a queued task that runs as a child of
whatever launched it.
