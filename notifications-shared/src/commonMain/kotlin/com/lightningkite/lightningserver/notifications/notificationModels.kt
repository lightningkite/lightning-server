package com.lightningkite.lightningserver.notifications

import com.lightningkite.services.data.GenerateDataClassPaths
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable
@GenerateDataClassPaths
public data class TimeInZone(
    val time: LocalTime,
    val zone: TimeZone
)

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
@ConsistentCopyVisibility
public data class Frequency private constructor(
    val onlyAt: TimeInZone?,
    val onlyOn: DayOfWeek?,
    val batchMinutes: Int?,
) {
    // CONSTRUCTORS
    public companion object {
        /**Creates a `NotificationFrequency` instance for immediate notifications.*/
        public fun immediately(): Frequency = Frequency(null, null, null)

        /**Creates a `NotificationFrequency` instance for daily notifications at a specific time and time zone.*/
        public fun daily(timeZone: TimeZone, time: LocalTime): Frequency = Frequency(TimeInZone(time, timeZone), null, null)

        /**Creates a `NotificationFrequency` instance for daily notifications at a specific hour and minute in a given time zone.*/
        public fun daily(hour: Int, minute: Int, timeZone: TimeZone = TimeZone.currentSystemDefault()): Frequency = daily(timeZone, LocalTime(hour, minute))

        /**Creates a `NotificationFrequency` instance for daily notifications at a specific time (in string format) and time zone.*/
        public fun daily(time: String, timeZone: TimeZone = TimeZone.currentSystemDefault()): Frequency = daily(timeZone, LocalTime.parse(time))

        /**Creates a `NotificationFrequency` instance for weekly notifications on a specific day, time, and time zone.*/
        public fun weekly(timeZone: TimeZone, weekDay: DayOfWeek, time: LocalTime): Frequency = Frequency(TimeInZone(time, timeZone), weekDay, null)

        /**Creates a `NotificationFrequency` instance for weekly notifications on a specific day, hour, and minute in a given time zone.*/
        public fun weekly(weekDay: DayOfWeek, hour: Int, minute: Int, timeZone: TimeZone = TimeZone.currentSystemDefault()): Frequency = weekly(timeZone, weekDay, LocalTime(hour, minute))

        /**Creates a `NotificationFrequency` instance for weekly notifications on a specific day and time (in string format) in a given time zone.*/
        public fun weekly(weekDay: DayOfWeek, time: String, timeZone: TimeZone = TimeZone.currentSystemDefault()): Frequency = weekly(timeZone, weekDay, LocalTime.parse(time))

        /**Creates a `NotificationFrequency` instance for batch notifications with a specified interval in minutes.*/
        public fun batch(minutes: Int): Frequency = Frequency(null, null, minutes)
    }

    // prioritizes longer specified time periods
    private fun weeklyAt(now: Instant): Instant? {
        val weekDay = onlyOn ?: return null
        val (time, timeZone) = onlyAt ?: return null

        val dateTime = now.toLocalDateTime(timeZone)
        val numDays = (dateTime.date.dayOfWeek.ordinal - weekDay.ordinal) % 7
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
    public fun sendAt(now: Instant): Instant =
        if (onlyAt == null && batchMinutes == null) now
        else weeklyAt(now) ?: dailyAt(now) ?: batchAt(now) ?: now
}

@Serializable
@GenerateDataClassPaths
public data class SendInfo(
    val sendAt: Instant,
    val sent: Boolean = false
)

@Serializable
@GenerateDataClassPaths
@IndexSet(["user", "sendAt",])
public data class NotificationForUser<UID, CONTENT>(
    override val _id: Uuid = Uuid.random(),
    val event: Event,
    val user: UID,
    val content: CONTENT,
    val createdAt: Instant,
    val read: Instant? = null,
    val email: SendInfo? = null,
    val push: SendInfo? = null,
    val sms: SendInfo? = null,
): HasId<Uuid>