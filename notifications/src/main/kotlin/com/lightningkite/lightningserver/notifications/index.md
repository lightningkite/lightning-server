# Notifications Package (JVM)

Server-side implementation of the Lightning Server notification system.

## Files

### NotificationEventHandler.kt

Core event-to-notification handler:

- Connects events to notification creation
- Manages content generation per event type
- Coordinates between subscription provider and dispatcher
- Handles errors gracefully with logging

### NotificationBulkDispatcher.kt

Notification queuing and delivery:

- Manages notification database table with REST endpoints and WebSocket updates
- Scheduled task system for finding and sending pending notifications
- Supports notification bulking (multiple notifications in one message)
- Delegates to abstract methods for:
    - Getting user contact info (email, phone, FCM tokens)
    - Formatting notifications per channel (with bulking)
- Automatic locking to prevent duplicate processing
- Supervisor scope for parallel channel processing

## Sub-packages

- **events/**: Type-safe event system with task-based launching
- **subscriptions/**: Three subscription provider implementations with varying customization levels

## Architecture

```
Event occurs
    ↓
EventHandler.handle()
    ↓
SubscriptionProvider.subscribed() → Determine interested users
    ↓
Content generation → Create CONTENT per user
    ↓
NotificationDispatcher.dispatch() → Insert into database
    ↓
Scheduler (every minute)
    ↓
Find pending notifications
    ↓
Group by user & channel
    ↓
Format & send (email, SMS, push, in-app)
```

## Key Concepts

- **Events are type-safe**: Use `TypedEvent<USER, T, ID>` in application code
- **Subscriptions determine audience**: Three models offer different customization levels
- **Content is user-specific**: Generate per-user content with access to subject entity
- **Bulking is transparent**: Multiple notifications scheduled at same time are grouped
- **Channels are independent**: Email, SMS, push, and in-app can have different schedules
