package com.lightningkite.lightningserver.notifications

import com.lightningkite.lightningserver.notifications.events.Event
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
import kotlin.time.Duration
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

@Serializable
@GenerateDataClassPaths
public data class SendInfo(
    val sendAt: Instant,
    val sent: Boolean = false
)

@Serializable
@GenerateDataClassPaths
@IndexSet(["user", "sendAt",])
public data class Notification<UID, CONTENT>(
    override val _id: Uuid = Uuid.random(),
    val event: Event,
    val user: UID,
    val content: CONTENT,
    val createdAt: Instant,
    val read: Instant? = null,
    val email: SendInfo? = null,
    val push: SendInfo? = null,
    val sms: SendInfo? = null,
    val inApp: SendInfo? = null,
): HasId<Uuid>


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

public fun <UID : Comparable<UID>> ScheduledSendMethods(
    user: UID,
    email: Frequency? = Frequency.immediately(),
    sms: Frequency? = Frequency.immediately(),
    push: Frequency? = Frequency.immediately(),
    inApp: Frequency? = Frequency.immediately()
): ScheduledSendMethods<UID> = ScheduledSendMethodsData(user, email, sms, push, inApp)