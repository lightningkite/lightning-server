package com.lightningkite.lightningserver.notifications


import com.lightningkite.UUID
import com.lightningkite.lightningdb.Condition
import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.Index
import com.lightningkite.lightningdb.IndexSet
import com.lightningkite.now
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes

typealias UntypedID = String
typealias SerializedCondition = String

@Serializable
@GenerateDataClassPaths
data class EventType(
    val name: String,
    val tags: Set<String> = emptySet()
) {
    override fun equals(other: Any?): Boolean = other is EventType && name == other.name
    override fun hashCode(): Int = name.hashCode()

    fun addTags(vararg tags: String) = copy(tags = this.tags+tags.toSet())
}

@Serializable
@GenerateDataClassPaths
data class Event(
    override val _id: UUID = UUID.random(),
    val timestamp: Instant,
    val type: EventType,
    val subject: UntypedID  // JSON of ID of T
): HasId<UUID>



@Serializable
@GenerateDataClassPaths
data class UserEventType<UID : Comparable<UID>>(val user: UID, val type: EventType) : Comparable<UserEventType<UID>> {
    override fun compareTo(other: UserEventType<UID>): Int =
        user.compareTo(other.user).takeIf { it != 0 } ?: type.name.compareTo(other.type.name)
}

@Serializable
@GenerateDataClassPaths
data class EventSubscription<UID : Comparable<UID>>(
    override val _id: UserEventType<UID>,
    val requestedFilter: SerializedCondition,  // JSON of Condition<T> (T is model type of event)
    val readPermissions: SerializedCondition,  // Calculated permissions for T for user
    @Index val filterHashes: Set<Int>,
    val frequency: NotificationFrequency,
    val email: Boolean,
    val push: Boolean,
    val sms: Boolean,
): HasId<UserEventType<UID>> {
    /**
     * Used for type-safe construction of [EventSubscription]. Not stored.
     * */
    @Serializable
    data class Info<T>(
        val filter: Condition<T> = Condition.Always,
        val frequency: NotificationFrequency = NotificationFrequency.immediately(),
        val email: Boolean = true,
        val push: Boolean = true,
        val sms: Boolean = true,
    )
}

interface NotificationContent {
    val title: String
    val body: String
    val url: String?

    @Serializable
    @GenerateDataClassPaths
    data class Basic(
        override val title: String,
        override val body: String,
        override val url: String? = null
    ) : NotificationContent

    companion object {
        operator fun invoke(
            title: String,
            body: String,
            url: String? = null,
        ) = Basic(title, body, url)
    }
}

@Serializable
@GenerateDataClassPaths
@IndexSet(["user", "sendAt",])
data class NotificationForUser<UID, CONTENT : NotificationContent>(
    override val _id: UUID = UUID.random(),
    val event: Event,
    val user: UID,
    val content: CONTENT,
    val sendAt: Instant,
    val createdAt: Instant = now(),
    val read: Instant? = null,
    val email: Boolean? = null, // null means do not send via that method, false = not sent yet, true = sent
    val push: Boolean? = null,
    val sms: Boolean? = null,
    val inAppOnlySent: Boolean = false // This is used to notify web sockets that the notification should be sent in-app if no other method is specified
): HasId<UUID>



/**
 * Represents the frequency at which notifications should be sent, with options for daily, weekly, batch-based, or immediate scheduling.
 *
 * @property onlyAt Specifies the exact time and time zone for sending notifications. Used for daily or weekly scheduling.
 * @property onlyOn Specifies the day of the week for sending notifications. Used for weekly scheduling.
 * @property batchMinutes Specifies the interval in minutes for batch-based notifications. Used for batch-based scheduling.
 *
 * The class prioritizes longer time periods (weekly > daily > batch > now) when determining the next notification time.
 */
@Serializable
@GenerateDataClassPaths
data class NotificationFrequency @Deprecated("Use a specific interval constructor directly. Ex. NotificationFrequency.immediately()") constructor(
    val onlyAt: TimeInZone?,
    val onlyOn: DayOfWeek?,
    val batchMinutes: Int?,
) {
    // CONSTRUCTORS
    @Suppress("DEPRECATION")
    companion object {
        /**Creates a `NotificationFrequency` instance for immediate notifications.*/
        fun immediately(): NotificationFrequency = NotificationFrequency(null, null, null)

        /**Creates a `NotificationFrequency` instance for daily notifications at a specific time and time zone.*/
        fun daily(timeZone: TimeZone, time: LocalTime) = NotificationFrequency(TimeInZone(time, timeZone), null, null)

        /**Creates a `NotificationFrequency` instance for daily notifications at a specific hour and minute in a given time zone.*/
        fun daily(hour: Int, minute: Int, timeZone: TimeZone = TimeZone.currentSystemDefault()) = daily(timeZone, LocalTime(hour, minute))

        /**Creates a `NotificationFrequency` instance for daily notifications at a specific time (in string format) and time zone.*/
        fun daily(time: String, timeZone: TimeZone = TimeZone.currentSystemDefault()) = daily(timeZone, LocalTime.parse(time))

        /**Creates a `NotificationFrequency` instance for weekly notifications on a specific day, time, and time zone.*/
        fun weekly(timeZone: TimeZone, weekDay: DayOfWeek, time: LocalTime) = NotificationFrequency(TimeInZone(time, timeZone), weekDay, null)

        /**Creates a `NotificationFrequency` instance for weekly notifications on a specific day, hour, and minute in a given time zone.*/
        fun weekly(weekDay: DayOfWeek, hour: Int, minute: Int, timeZone: TimeZone = TimeZone.currentSystemDefault()) = weekly(timeZone, weekDay, LocalTime(hour, minute))

        /**Creates a `NotificationFrequency` instance for weekly notifications on a specific day and time (in string format) in a given time zone.*/
        fun weekly(weekDay: DayOfWeek, time: String, timeZone: TimeZone = TimeZone.currentSystemDefault()) = weekly(timeZone, weekDay, LocalTime.parse(time))

        /**Creates a `NotificationFrequency` instance for batch notifications with a specified interval in minutes.*/
        fun batch(minutes: Int) = NotificationFrequency(null, null, minutes)
    }

    // prioritizes longer specified time periods
    private fun weeklyAt(now: Instant): Instant? {
        val weekDay = onlyOn ?: return null
        val (time, timeZone) = onlyAt ?: return null

        val dateTime = now.toLocalDateTime(timeZone)
        val numDays = (dateTime.date.dayOfWeek.ordinal - weekDay.ordinal)%7
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
     * Determines when a notification should be sent.
     * @param now The instant at which the notification was created
     * @return The instant at which the notification should be sent
     * */
    fun sendAt(now: Instant): Instant =
        if (onlyAt == null && batchMinutes == null) now
        else weeklyAt(now) ?: dailyAt(now) ?: batchAt(now) ?: now
}

@Serializable
@GenerateDataClassPaths
data class TimeInZone(
    val time: LocalTime,
    val zone: TimeZone
)