# Subscriptions Package

This package contains shared data models for notification subscriptions.

## Files

### subscriptionModels.kt
Subscription configuration models:
- **NotificationEventSubscription**: Full subscription with user-defined filters and delivery frequencies
- **NotificationSendMethods**: Simplified subscription with only delivery frequency customization
- **Subscription**: Type-safe subscription configuration for defining defaults
- **SerializedCondition**: Type alias for JSON-serialized filter conditions

These models support different subscription strategies:
- **NotificationEventSubscription** is used by FullyCustomizableSubscriptions for maximum user control
- **NotificationSendMethods** is used by FrequencyCustomizableSubscriptions where logic determines interested users
- **Subscription** is a type-safe helper for defining default subscriptions in code
