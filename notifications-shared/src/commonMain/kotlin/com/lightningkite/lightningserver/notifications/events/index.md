# Events Package

This package contains shared data models for the event system in Lightning Server notifications.

## Files

### eventModels.kt

Core event data models:

- **EventType**: Represents a type of event with a unique name (`EventType.Name`) and optional tags
- **EventData**: An event occurrence in untyped, serialized form, carried by stored notifications
- **UserEventType**: Composite key combining a user ID and event type, used for subscriptions

These models are the foundation for tracking events that trigger notifications. Events are stored in an untyped form,
with a JSON-serialized subject, so that one table can hold events about many entity types.
