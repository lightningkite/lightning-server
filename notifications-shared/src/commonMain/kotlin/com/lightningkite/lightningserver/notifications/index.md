# Notifications Package (Shared)

Core multiplatform models for the Lightning Server notification system.

## Files

### notificationModels.kt
Primary notification and scheduling models:
- **Frequency**: Configurable delivery schedule (immediate, batch, daily, weekly, delayed)
- **TimeInZone**: Time specification with timezone
- **SendInfo**: Tracks when a notification should be/was sent on a specific channel
- **Notification**: The complete notification entity with content and multi-channel delivery info
- **ScheduledSendMethods**: Interface for specifying delivery frequencies per channel

The notification system supports four delivery channels: email, SMS, push notifications, and in-app. Each can be scheduled independently with different frequencies.

## Sub-packages

- **events/**: Event type definitions and event occurrence models
- **subscriptions/**: Subscription configuration models for different customization levels
