package com.lightningkite.lightningserver.notifications

import com.lightningkite.lightningserver.notifications.events.Event
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.data.Index
import com.lightningkite.services.data.IndexSet
import com.lightningkite.services.database.HasId
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Represents a specific time in a specific time zone.
 *
 * @property time The local time (hour and minute)
 * @property zone The time zone in which this time should be interpreted
 */
@Serializable
@GenerateDataClassPaths
public data class TimeInZone(
    val time: LocalTime,
    val zone: TimeZone
)

/**
 * Represents the frequency at which something should be scheduled, with options for daily, weekly, batch-based, or immediate scheduling.
 *
 * @property onlyAt Specifies the exact time and time zone for daily or weekly scheduling.
 * @property onlyOn Specifies the day of the week for weekly scheduling.
 * @property batchMinutes Specifies the interval in minutes for batch-based scheduling.
 * @property delay Specifies an optional delay to be added to the schedule.
 *
 * The class prioritizes longer time periods (weekly > daily > batch > now) when determining the next notification time.
 */
@Serializable
@GenerateDataClassPaths
@ConsistentCopyVisibility
public data class Frequency private constructor(
    val onlyAt: TimeInZone?,
    val onlyOn: DayOfWeek?,
    val batchMinutes: Int?,
    val delay: Duration?
) {
    // CONSTRUCTORS
    public companion object {
        /**Creates a [Frequency] instance for immediate scheduling.*/
        public fun immediately(): Frequency = Frequency(null, null, null, null)

        /**Creates a [Frequency] instance for delayed scheduling*/
        public fun delayed(duration: Duration): Frequency = Frequency(null, null, null, duration)

        /**Creates a [Frequency] instance for daily scheduling at a specific time and time zone.*/
        public fun daily(timeZone: TimeZone, time: LocalTime): Frequency = Frequency(TimeInZone(time, timeZone), null, null, null)

        /**Creates a [Frequency] instance for daily scheduling at a specific hour and minute in a given time zone.*/
        public fun daily(hour: Int, minute: Int, timeZone: TimeZone = TimeZone.currentSystemDefault()): Frequency = daily(timeZone, LocalTime(hour, minute))

        /**Creates a [Frequency] instance for daily scheduling at a specific time (in string format) and time zone.*/
        public fun daily(time: String, timeZone: TimeZone = TimeZone.currentSystemDefault()): Frequency = daily(timeZone, LocalTime.parse(time))

        /**Creates a [Frequency] instance for weekly scheduling on a specific day, time, and time zone.*/
        public fun weekly(timeZone: TimeZone, weekDay: DayOfWeek, time: LocalTime): Frequency = Frequency(TimeInZone(time, timeZone), weekDay, null, null)

        /**Creates a [Frequency] instance for weekly scheduling on a specific day, hour, and minute in a given time zone.*/
        public fun weekly(weekDay: DayOfWeek, hour: Int, minute: Int, timeZone: TimeZone = TimeZone.currentSystemDefault()): Frequency = weekly(timeZone, weekDay, LocalTime(hour, minute))

        /**Creates a [Frequency] instance for weekly scheduling on a specific day and time (in string format) in a given time zone.*/
        public fun weekly(weekDay: DayOfWeek, time: String, timeZone: TimeZone = TimeZone.currentSystemDefault()): Frequency = weekly(timeZone, weekDay, LocalTime.parse(time))

        /**Creates a [Frequency] instance for batch scheduling with a specified interval in minutes.*/
        public fun batch(minutes: Int): Frequency = Frequency(null, null, minutes, null)
    }

    private fun weeklyAt(now: Instant): Instant? {
        val weekDay = onlyOn ?: return null
        val (time, timeZone) = onlyAt ?: return null

        val dateTime = now.toLocalDateTime(timeZone)
        val numDays = (weekDay.ordinal - dateTime.date.dayOfWeek.ordinal) % 7
        var sendAt = LocalDateTime(dateTime.date.plus(numDays, DateTimeUnit.DAY), time).toInstant(timeZone)
        if (sendAt < now) {
            sendAt = sendAt.plus(1, DateTimeUnit.WEEK, timeZone)
        }

        return sendAt
    }

    private fun dailyAt(now: Instant): Instant? {
        val (time, timeZone) = onlyAt ?: return null

        val dateTime = now.toLocalDateTime(timeZone)
        var sendAt = LocalDateTime(dateTime.date, time).toInstant(timeZone)
        if (sendAt < now) {
            sendAt = sendAt.plus(1, DateTimeUnit.DAY, timeZone)
        }
        return sendAt
    }

    private fun batchAt(now: Instant): Instant? {
        val minutes = batchMinutes ?: return null

        val minutesUntilSend = minutes - (now.toLocalDateTime(TimeZone.UTC).time.run { hour*60 + minute } % minutes)
        return now + minutesUntilSend.minutes
    }

    /**
     * Calculates the instant where this [Frequency] next occurs.
     * */
    public fun schedule(now: Instant): Instant {
        val scheduled =
            if (onlyAt == null && batchMinutes == null) now
            else weeklyAt(now) ?: dailyAt(now) ?: batchAt(now) ?: now

        return if (delay != null) scheduled + delay else scheduled
    }

    /**
     * Adds a delay to this [Frequency]
     * */
    public fun delayed(duration: Duration): Frequency = copy(delay = duration)
}

/**
 * Tracks the send status for a specific notification delivery method.
 *
 * @property sendAt The scheduled time to send this notification
 * @property sent Whether the notification has been successfully sent
 */
@Serializable
@GenerateDataClassPaths
public data class SendInfo(
    val sendAt: Instant,
    val sent: Boolean = false
)

/**
 * Represents a notification to be delivered to a user.
 *
 * Notifications are created in response to events and contain the content to be delivered
 * through various channels (email, SMS, push, in-app). Each channel has optional [SendInfo]
 * that tracks when and whether the notification was sent.
 *
 * The database indexes on [user] and [sendAt] to efficiently query pending notifications.
 *
 * @param UID The type of user identifier
 * @param CONTENT The type of notification content (application-specific)
 * @property _id Unique identifier for this notification
 * @property event The event that triggered this notification
 * @property user The user who should receive this notification
 * @property content The notification content (format determined by application)
 * @property createdAt When this notification was created
 * @property read When the user marked this notification as read (null if unread)
 * @property email Email delivery info, or null if email delivery is disabled
 * @property push Push notification delivery info, or null if push is disabled
 * @property sms SMS delivery info, or null if SMS is disabled
 * @property inApp In-app notification delivery info, or null if in-app is disabled
 */
@Serializable
@GenerateDataClassPaths
public data class Notification<UID, CONTENT>(
    override val _id: Uuid = Uuid.random(),
    val event: Event,
    @Index val user: UID,
    val content: CONTENT,
    val createdAt: Instant,
    val read: Instant? = null,
    val email: SendInfo? = null,
    val push: SendInfo? = null,
    val sms: SendInfo? = null,
    val inApp: SendInfo? = null,
): HasId<Uuid>


/**
 * Interface for objects that specify notification delivery frequencies for each channel.
 *
 * Used to determine when notifications should be sent for a specific user and event type.
 * A null frequency indicates that channel is disabled for this subscription.
 *
 * @param UID The type of user identifier
 */
public interface ScheduledSendMethods<UID : Comparable<UID>> {
    public val user: UID
    public val email: Frequency?
    public val sms: Frequency?
    public val push: Frequency?
    public val inApp: Frequency?
}

private data class ScheduledSendMethodsData<UID : Comparable<UID>>(
    override val user: UID,
    override val email: Frequency?,
    override val sms: Frequency?,
    override val push: Frequency?,
    override val inApp: Frequency?
) : ScheduledSendMethods<UID>

/**
 * Factory function to create a [ScheduledSendMethods] instance.
 *
 * @param user The user identifier
 * @param email Email delivery frequency (defaults to immediate)
 * @param sms SMS delivery frequency (defaults to immediate)
 * @param push Push notification delivery frequency (defaults to immediate)
 * @param inApp In-app notification delivery frequency (defaults to immediate)
 */
public fun <UID : Comparable<UID>> ScheduledSendMethods(
    user: UID,
    email: Frequency? = Frequency.immediately(),
    sms: Frequency? = Frequency.immediately(),
    push: Frequency? = Frequency.immediately(),
    inApp: Frequency? = Frequency.immediately()
): ScheduledSendMethods<UID> = ScheduledSendMethodsData(user, email, sms, push, inApp)

/*
 * TODO: API Recommendations for notificationModels.kt:
 *
 * 1. Add a helper method to Frequency for common patterns:
 *    - Frequency.disabled() or Frequency.never() to explicitly represent disabled channels
 *    - Consider if null should represent "not configured" vs "disabled"
 *
 * 2. Consider adding validation to Frequency:
 *    - batchMinutes should probably have a minimum value (e.g., 1 minute)
 *    - Maximum batch interval might be useful
 *
 * 3. The Notification class could benefit from helper methods:
 *    - isRead(): Boolean = read != null
 *    - hasUnsentChannels(at: Instant): Boolean to check if any channel needs sending
 *    - needsSending(at: Instant): Boolean to consolidate the private needsSending logic
 *
 * 4. Consider adding a MarkAsReadModification or similar to make marking notifications read
 *    easier and more consistent across implementations.
 *
 * 5. The IndexSet annotation only includes ["user", "sendAt"] but queries likely need to filter
 *    by sent status and sendAt together. Consider adding a composite index like
 *    ["user", "email.sent", "email.sendAt"] or similar for each channel.
 */