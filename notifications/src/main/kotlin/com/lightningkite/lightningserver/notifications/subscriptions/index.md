# Subscriptions Package

Server-side subscription providers for the notification system.

## Files

### NonCustomizableSubscriptions.kt

Fully programmatic subscription management:

- No database storage for subscriptions
- Subscribers for each event are defined in code, with `subscribedDirect` inside the event's setup block
- Results are merged, taking earliest schedule time per channel
- Best for mandatory notifications or complete programmatic control

### FrequencyCustomizableSubscriptions.kt

User-customizable frequency with programmatic logic:

- Database storage for per-user frequency preferences (NotificationSendMethods table)
- Interest determination remains programmatic
- Users can override default frequencies via REST endpoints
- Good balance between flexibility and control

### FullyCustomizableSubscriptions.kt

Maximum user customization:

- Database storage for full subscriptions (NotificationEventSubscription table)
- Users can define custom filter conditions
- Automatic default subscription management on user create/update/delete
- Enforces read permissions automatically
- Three update behaviors for handling default changes:
    - `UpdateReadPermissions`: Only update permissions (preserves user changes)
    - `ReplaceExistingWithDefault`: Overwrite with new defaults (discards user changes)
    - `UpdateRetainingUserChanges`: Smart merge that preserves user modifications

All providers implement `NotificationEndpoints.Subscriptions` and determine which users should be notified for each event
occurrence.
