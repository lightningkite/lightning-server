# Notifications Package (JVM)

Server-side implementation of the Lightning Server notification system.

## Files

### NotificationEndpoints.kt

The central coordinator, which connects events to subscriptions and dispatchers:

- **NotificationEndpoints**: Receives events, asks its `Subscriptions` provider who should be notified, generates
  per-user content, and hands the notifications to its `Dispatcher`. Extend it with an object and define events inside
- **NotificationEndpoints.Subscriptions**: Decides which users are notified about an event, and how often per channel
- **NotificationEndpoints.Dispatcher**: Stores and delivers notifications
- **NotificationEndpoints.ContentRegistry**: The content generator for each event type, set with `EventDefinition.content`.
  A startup task verifies that every event has one

### NotificationBulkDispatcher.kt

The built-in `Dispatcher`, handling queuing and delivery:

- Manages notification database table with REST endpoints and WebSocket updates
- Scheduled task system for finding and sending pending notifications
- Supports notification bulking (multiple notifications in one message)
- Delegates to abstract methods for:
    - Getting user contact info (email, phone, FCM tokens)
    - Formatting notifications per channel (with bulking)
- Automatic locking to prevent duplicate processing
- Supervisor scope for parallel channel processing

### Sequence.ext.kt

In-memory `query` and `withPermissions` for sequences, for providers that compute results without a table.

## Sub-packages

- **events/**: Event definitions and task-based launching
- **subscriptions/**: Three subscription provider implementations with varying customization levels

## Architecture

```
Event launched (EventLauncher invoked)
    ↓
NotificationEndpoints.handle()
    ↓
Subscriptions.subscribed() → Determine interested users
    ↓
Content generation → Create CONTENT per user
    ↓
Dispatcher.dispatch() → Insert into database
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

- **Events are type-safe**: An `EventDefinition<T, ID>` names an event about subjects of type `T`, and an `Event<T, ID>`
  carries the subject itself
- **Subscriptions determine audience**: Three providers offer different customization levels
- **Content is user-specific**: Generate per-user content with access to the subject entity
- **Bulking is transparent**: Multiple notifications scheduled at same time are grouped
- **Channels are independent**: Email, SMS, push, and in-app can have different schedules
