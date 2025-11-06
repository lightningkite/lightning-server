package com.lightningkite.lightningserver.notifications.subscriptions

import com.lightningkite.lightningserver.notifications.Frequency
import com.lightningkite.lightningserver.notifications.ScheduledSendMethods
import com.lightningkite.lightningserver.notifications.events.UserEventType
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Serializable

/**
 * Type alias for JSON-serialized [Condition] objects.
 * Used to store type-erased conditions in database tables that handle multiple event types.
 */
public typealias SerializedCondition = String

/**
 * Represents a user's fully customizable subscription to an event type.
 *
 * This subscription model allows users to specify both filtering conditions and delivery frequencies
 * for each notification channel. It's used by [FullyCustomizableSubscriptions].
 *
 * **Important:** The final condition for notification delivery is the intersection (AND) of
 * [requestedFilter] and [readPermissions]. This ensures users only receive notifications for
 * events they have permission to see.
 *
 * @param UID The type of user identifier
 * @property _id Composite key of user and event type
 * @property requestedFilter User's desired filter condition (JSON-serialized)
 * @property readPermissions System-calculated read permissions for this user (JSON-serialized)
 * @property email Email delivery frequency, or null to disable email notifications
 * @property push Push notification delivery frequency, or null to disable push notifications
 * @property sms SMS delivery frequency, or null to disable SMS notifications
 * @property inApp In-app notification delivery frequency, defaults to immediate
 */
@Serializable
@GenerateDataClassPaths
public data class NotificationEventSubscription<UID : Comparable<UID>>(
    override val _id: UserEventType<UID>,
    val requestedFilter: SerializedCondition,
    val readPermissions: SerializedCondition,  // Calculated permissions for T for user
    override val email: Frequency?,
    override val push: Frequency?,
    override val sms: Frequency?,
    override val inApp: Frequency? = Frequency.immediately()
): HasId<UserEventType<UID>>, ScheduledSendMethods<UID> {
    override val user: UID get() = _id.user
}

/**
 * Represents delivery frequency preferences for an event type subscription.
 *
 * This simplified model only allows customization of delivery frequencies, not filtering conditions.
 * Used by [FrequencyCustomizableSubscriptions] where the interested users and conditions are
 * determined programmatically rather than by user configuration.
 *
 * @param UID The type of user identifier
 * @property _id Composite key of user and event type
 * @property email Email delivery frequency, or null to disable email notifications
 * @property push Push notification delivery frequency, or null to disable push notifications
 * @property sms SMS delivery frequency, or null to disable SMS notifications
 * @property inApp In-app notification delivery frequency, defaults to immediate
 */
@Serializable
@GenerateDataClassPaths
public data class NotificationSendMethods<UID : Comparable<UID>>(
    override val _id: UserEventType<UID>,
    override val email: Frequency?,
    override val push: Frequency?,
    override val sms: Frequency?,
    override val inApp: Frequency? = Frequency.immediately()
): HasId<UserEventType<UID>>, ScheduledSendMethods<UID> {
    override val user: UID get() = _id.user
}

/**
 * Type-safe subscription configuration for a specific event type.
 *
 * Used in [FullyCustomizableSubscriptions] to define default subscriptions for users.
 * Unlike the database models above, this maintains type safety for the filtered entity.
 *
 * @param T The type of entity being filtered
 * @property filter Condition that determines which events match this subscription
 * @property email Email delivery frequency, or null to disable email notifications
 * @property push Push notification delivery frequency, or null to disable push notifications
 * @property sms SMS delivery frequency, or null to disable SMS notifications
 * @property inApp In-app notification delivery frequency, defaults to immediate
 */
@Serializable
public data class Subscription<T>(
    val filter: Condition<T>,
    val email: Frequency?,
    val push: Frequency?,
    val sms: Frequency?,
    val inApp: Frequency? = Frequency.immediately()
)