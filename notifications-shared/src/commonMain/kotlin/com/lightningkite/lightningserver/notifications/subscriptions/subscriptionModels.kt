package com.lightningkite.lightningserver.notifications.subscriptions

import com.lightningkite.lightningserver.notifications.Frequency
import com.lightningkite.lightningserver.notifications.ScheduledSendMethods
import com.lightningkite.lightningserver.notifications.events.UserEventType
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Serializable

/** JSON of Condition<T> (used to keep untyped information in the same table) */
public typealias SerializedCondition = String

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

@Serializable
public data class Subscription<T>(
    val filter: Condition<T>,
    val email: Frequency?,
    val push: Frequency?,
    val sms: Frequency?,
    val inApp: Frequency? = Frequency.immediately()
)