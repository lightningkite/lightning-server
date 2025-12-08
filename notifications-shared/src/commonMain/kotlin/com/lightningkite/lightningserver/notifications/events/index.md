# Events Package

This package contains shared data models for the event system in Lightning Server notifications.

## Files

### eventModels.kt
Core event data models:
- **EventType**: Represents a type of event with a unique name and optional tags
- **Event**: Database-stored event occurrence with serialized subject ID
- **UserEventType**: Composite key combining a user ID and event type, used for subscriptions
- **UntypedID**: Type alias for JSON-serialized IDs

These models are the foundation for tracking events that trigger notifications. Events are stored in an untyped form (with JSON-serialized IDs) to allow a single table to handle events for multiple entity types.
