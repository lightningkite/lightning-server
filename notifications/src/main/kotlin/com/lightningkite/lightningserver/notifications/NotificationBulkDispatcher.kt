package com.lightningkite.lightningserver.notifications

import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.data.Schedule
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.typed.sdk.SdkModule
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.defaultInfo
import com.lightningkite.lightningserver.typed.sdk.sdkSettings
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import com.lightningkite.services.email.Email
import com.lightningkite.services.email.EmailService
import com.lightningkite.services.notifications.*
import com.lightningkite.services.sms.SMS
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.uuid.Uuid


/**
 * `NotificationDispatcher` is in charge of queuing, bulking, formatting, and sending notifications.
 *
 * ## Overview
 * When a [Notification] is created, it specifies when the notification should be sent via email, sms, and/or push notification.
 * The scheduler checks existing notifications for this information, finding notifications which have been "scheduled" for sending, and sends them
 * via the appropriate method.
 *
 * In addition to sending notifications, the scheduler also supports notification bulking and formatting, both of which are controlled by the implementor.
 * If multiple notifications for a user are scheduled to send via the same method at the same time, the scheduler can group these notifications
 * to send in a single notification, or multiple depending on how the user configures it.
 *
 * Note that the scheduler is not in charge of creating notifications or determining when they should be sent. It is only responsible for finding
 * scheduled notifications, bulking them, formatting them, and sending them.
 *
 * ## Usage
 * - Provide only the [ModelInfo] for your notifications, both [ModelRestEndpoints] and [ModelRestUpdatesWebsocket] will be automatically created
 * - Provide your `USER` contact information by implementing the various abstract methods.
 * - To control bulking and formatting of notifications, override the appropriate `make{method}Notifications` function. These take in a user and the list of
 *   scheduled notifications, and returns the formatted and bulked list of method-specific send formats.
 */
public abstract class NotificationBulkDispatcher<USER : HasId<UID>, UID : Comparable<UID>, CONTENT>(
    public val info: ModelInfo<*, Notification<UID, CONTENT>, Uuid>,
    public val cache: Runtime<Cache>,
    database: Runtime<Database>,
    public val users: ModelInfo<*, USER, UID>,
    public val contentSerializer: KSerializer<CONTENT>,
    public val email: (Runtime<EmailService>)? = null,
    public val sms: (Runtime<SMS>)? = null,
    public val push: (Runtime<NotificationService>)? = null,
    public val refreshSchedule: Schedule = Schedule.Frequency(1.minutes),
    public val timeout: Duration = 5.minutes,
    websocketKey: SerializableProperty<Notification<UID, CONTENT>, *>? = info.serializer.fieldInApp,
) : ServerBuilder(), NotificationEndpoints.Dispatcher<UID, CONTENT> {
    init {
        sdkSettings.defaultInfo = SdkModule.Info("NotificationsApi")
    }

    /**
     * Returns the email address for a user, or null if email notifications are not enabled for this user.
     * @param user The user to get the email address for
     */
    context(server: ServerRuntime)
    public abstract suspend fun email(user: USER): EmailAddress?

    /**
     * Returns the phone number for a user, or null if SMS notifications are not enabled for this user.
     * @param user The user to get the phone number for
     */
    context(server: ServerRuntime)
    public abstract suspend fun phone(user: USER): PhoneNumber?

    /**
     * Returns the set of FCM (Firebase Cloud Messaging) tokens for a user's devices.
     * @param user The user to get FCM tokens for
     * @return Set of FCM tokens, or empty set if push notifications are not enabled
     */
    context(server: ServerRuntime)
    public abstract suspend fun fcmTokens(user: USER): Set<String>

    /**
     * Called when FCM tokens are detected as invalid or expired.
     * Implementations should remove these tokens from the user's profile.
     * @param user The user whose tokens are dead
     * @param deadTokens The set of tokens that are no longer valid
     */
    context(server: ServerRuntime)
    public abstract suspend fun onFcmTokensDead(user: USER, deadTokens: Set<String>)

    internal val logger: KLogger =
        KotlinLogging.logger("com.lightningkite.lightningserver.notifications.NotificationBulkDispatcher")

    /**
     * Additional condition to apply when querying for notifications to send.
     * Override this to add custom filtering logic (e.g., only send notifications for active users).
     */
    protected open val additionalSendCondition: Condition<Notification<UID, CONTENT>>? = null

    /**
     * Generates a list of [Email] to be sent to the user based on the provided notifications.
     *
     * Override this method to customize the email content for bulk notifications.
     *
     * @param user The user to whom the emails will be sent.
     * @param notifications The list of notifications to be included in the emails.
     * @return A list of `Email` to be sent.
     * */
    context(runtime: ServerRuntime)
    public abstract suspend fun makeEmailNotifications(
        user: USER,
        notifications: List<Notification<UID, CONTENT>>,
    ): List<Email>

    /**
     * Generates a list of SMS messages to be sent to the user based on the provided notifications.
     *
     * Override this method to customize the SMS content for bulk notification
     *
     * @param user The user to whom the SMS messages will be sent.
     * @param notifications The list of notifications to be included in the SMS messages.
     * @return A list of SMS messages as strings to be sent.
     * */
    context(runtime: ServerRuntime)
    public abstract suspend fun makeSmsNotifications(
        user: USER,
        notifications: List<Notification<UID, CONTENT>>,
    ): List<String>

    /**
     * Creates a list of push notifications to be sent to the user based on the provided notifications.
     *
     * Override this method to customize the content of push notifications for bulk delivery.
     *
     * @param user The user who will receive the push notifications.
     * @param notifications The list of notifications to include in the push notifications.
     * @return A list of `NotificationData` objects representing the push notifications to be sent.
     */
    context(runtime: ServerRuntime)
    public abstract suspend fun makePushNotifications(
        user: USER,
        notifications: List<Notification<UID, CONTENT>>,
    ): List<NotificationData>


    private fun SendInfo?.needsSending(at: Instant) = this != null && !sent && sendAt <= at

    context(runtime: ServerRuntime)
    override suspend fun dispatch(notifications: List<Notification<UID, CONTENT>>) {
        val now = now()

        info.table().insertMany(notifications)

        notifications
            .filter {
                it.email.needsSending(now) || it.sms.needsSending(now) || it.push.needsSending(now) || it.inApp.needsSending(
                    now
                )
            }
            .takeIf { it.isNotEmpty() }
            ?.let { sendNotifications(it) }
    }

    /**
     * REST and WebSocket endpoints for managing notifications.
     * Mounted at `/rest`. Provides CRUD operations and real-time updates for notifications.
     */
    public val rest: ModelRestEndpointsAndUpdatesWebsocket<*, Notification<UID, CONTENT>, Uuid> =
        path.path("rest") include ModelRestEndpointsAndUpdatesWebsocket(info, websocketKey)

    //_____Refreshing and sending notifications_____
    // Notifications are created with a 'sendAt' time, this specifies when the notification should be sent
    // and is how groups of notifications are bulked together. Every minute refreshNotifications queries for
    // unsent notifications whose sendAt time is in the past, and sends them.
    //
    // Notifications with overlapping sendAts are bulked together per user.
    //
    // Checking for queued notifications and sending them is split into two actions: refreshNotifications
    // and sendNotifications.

    private val self = DataClassPathSelf(info.serializer)

    private val sendLogger = KotlinLogging.logger("${logger.name}.send")

    context(runtime: ServerRuntime)
    private suspend fun sendEmailNotifications(user: USER, notifications: List<Notification<UID, CONTENT>>) {
        if (email == null) {
            sendLogger.debug { "No EmailService settings provided, skipping emails" }
            return
        }
        if (email(user) == null) {
            sendLogger.debug { "No email found for user ${user._id}" }
            return
        }

        val emails = makeEmailNotifications(user, notifications)
        if (emails.isNotEmpty()) email().sendBulk(emails)
    }

    context(runtime: ServerRuntime)
    private suspend fun sendSmsNotifications(user: USER, notifications: List<Notification<UID, CONTENT>>) {
        if (sms == null) {
            sendLogger.debug { "No SMS settings provided, skipping SMS" }
            return
        }
        val phoneNumber = phone(user) ?: run {
            sendLogger.debug { "No phone number found for user ${user._id}, skipping SMS" }
            return
        }

        val messages = makeSmsNotifications(user, notifications)
        if (messages.isEmpty()) return
        val sms = sms()
        messages.forEach { sms.send(phoneNumber, it) }
    }

    context(runtime: ServerRuntime)
    private suspend fun sendPushNotifications(user: USER, notifications: List<Notification<UID, CONTENT>>) {
        if (push == null) {
            sendLogger.debug { "No NotificationService settings provided, skipping push notifications" }
            return
        }
        val allTokens = fcmTokens(user).toMutableSet()
        if (allTokens.isEmpty()) {
            sendLogger.debug { "No fcm tokens found for user ${user._id}, skipping push notifications" }
            return
        }

        val notificationData = makePushNotifications(user, notifications)
        if (notificationData.isEmpty()) return

        val push = push()
        val deadTokens = HashSet<String>()
        notificationData.forEach {
            val r = push.send(allTokens.toList(), it)
            r.forEach { (a, b) ->
                when (b) {
                    NotificationSendResult.DeadToken -> deadTokens.add(a)
                    NotificationSendResult.Failure -> {}
                    NotificationSendResult.Success -> {}
                }
            }
        }
        if (deadTokens.isNotEmpty()) onFcmTokensDead(user, deadTokens)
    }

    /**
     * Pagination parameters for querying notifications.
     * @property page The current page number (0-indexed)
     * @property pageLimit The number of notifications per page
     */
    @Serializable
    public data class BasicPager(
        val page: Int,
        val pageLimit: Int,
    )

    /**
     * Payload for the send notifications task, including pre-fetched user data.
     * @property users Map of user IDs to user objects for efficient lookup
     * @property notifications The notifications to send
     */
    @Serializable
    public data class NotificationPager<USER : HasId<UID>, UID : Comparable<UID>, CONTENT>(
        val users: Map<UID, USER>,
        val notifications: List<Notification<UID, CONTENT>>,
    )

    /**
     * Task that sends a batch of notifications to users.
     *
     * Processes notifications in parallel per user, sending email, SMS, push, and in-app
     * notifications as configured. Marks each channel as sent after successful delivery.
     * If the task times out before all notifications are sent, it automatically relaunches
     * with the remaining notifications.
     */
    public val sendNotifications: Task<NotificationPager<USER, UID, CONTENT>> =
        path.path("send-notifs") bind Task(
            NotificationPager.serializer(
                users.serializer,
                users.idSerializer,
                contentSerializer
            )
        ) { startInfo ->
            val byUser = startInfo.notifications.groupBy { it.user }

            val now = now()

            val unsent = runForEach(timeout, byUser.entries) { (userId, userNotifs) ->
                try {
                    val user = startInfo.users[userId]
                        ?: throw NotFoundException("User could not be found to send notifications: $userId")

                    supervisorScope {
                        launch {
                            val toEmail = userNotifs.filter { it.email.needsSending(now) }
                            if (toEmail.isEmpty()) return@launch
                            try {
                                sendEmailNotifications(user, toEmail)
                                info.table().updateManyIgnoringResult(
                                    self._id inside toEmail.map { it._id },
                                    modification(self) {
                                        it.email.notNull.sent assign true
                                    }
                                )
                            } catch (e: Exception) {
                                logger.error(e) { "Failed to send email notifications" }
                            }
                        }
                        launch {
                            val toSms = userNotifs.filter { it.sms.needsSending(now) }
                            if (toSms.isEmpty()) return@launch
                            try {
                                sendSmsNotifications(user, toSms)
                                info.table().updateManyIgnoringResult(
                                    self._id inside toSms.map { it._id },
                                    modification(self) {
                                        it.sms.notNull.sent assign true
                                    }
                                )
                            } catch (e: Exception) {
                                logger.error(e) { "Failed to send sms notifications" }
                            }
                        }
                        launch {
                            val toPush = userNotifs.filter { it.push.needsSending(now) }
                            if (toPush.isEmpty()) return@launch
                            try {
                                sendPushNotifications(user, toPush)
                                info.table().updateManyIgnoringResult(
                                    self._id inside toPush.map { it._id },
                                    modification(self) {
                                        it.push.notNull.sent assign true
                                    }
                                )
                            } catch (e: Exception) {
                                logger.error(e) { "Failed to send push notifications" }
                            }
                        }
                        launch {
                            val inApp = userNotifs.filter { it.inApp.needsSending(now) }
                            if (inApp.isEmpty()) return@launch
                            try {
                                info.table().updateManyIgnoringResult(
                                    self._id inside inApp.map { it._id },
                                    modification(self) {
                                        it.inApp.notNull.sent assign true
                                    }
                                )
                            } catch (e: Exception) {
                                logger.error(e) { "Failed to send in-app notifications" }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Exception occurred in $location for user $userId" }
                }
            }

            if (unsent.isNotEmpty()) launch(NotificationPager(startInfo.users, unsent.flatMap { it.value }))
        }

    /**
     * Fetches users for the given notifications and launches the [sendNotifications] task.
     *
     * This is the main entry point for sending a batch of notifications. It automatically
     * fetches user data required for delivery and invokes the task.
     *
     * @param notifications The notifications to send
     */
    context(_: ServerRuntime)
    public suspend fun sendNotifications(notifications: List<Notification<UID, CONTENT>>) {
        val users = users.table()
            .getMany(notifications.map { it.user })
            .associateBy { it._id }

        sendNotifications(NotificationPager(users, notifications))
    }

    /**
     * Singleton record tracking the last time notifications were refreshed.
     * Used to avoid re-sending notifications that have already been processed.
     * @property instant The timestamp of the last refresh
     */
    @Serializable
    @GenerateDataClassPaths
    public data class RunInstant(val instant: kotlin.time.Instant) : HasId<String> {
        public companion object {
            /** The singleton ID for this record */
            public const val ID: String = "SINGLETON"
        }

        @Transient
        override val _id: String = ID
    }

    private val lastRunInfo = database.explicitModelInfo(
        auth = noAuth,
        serializer = RunInstant.serializer(),
        idSerializer = String.serializer(),
        permissions = { ModelPermissions() }
    )

    // Finds notifications that need to be sent.
    // Importantly, this does not look for all notifications with sendAt times in the past,
    // only sendAt times between the last time checked and now. This means that if notifications
    // attempted to send and failed, sending won't be attempted a second time.
    context(_: ServerRuntime)
    private val scheduleLockKey get() = "${refreshNotifications.location}.lockKey"

    private fun <K> DataClassPath<K, SendInfo>.shouldBeSentNow(lower: Instant, upper: Instant) =
        Condition.And(sent eq false, sendAt gt lower, sendAt lte upper)

    private suspend fun <T> runFor(timeout: Duration, startingValue: T, action: suspend (T) -> T?): T? {
        val loopStart = TimeSource.Monotonic.markNow()
        var value = startingValue

        while (loopStart.elapsedNow() < timeout) {
            value = action(value) ?: return null
        }

        return value
    }

    private suspend fun <T> runForEach(timeout: Duration, items: Collection<T>, action: suspend (T) -> Unit): List<T> {
        val loopStart = TimeSource.Monotonic.markNow()

        val remaining = items.toMutableList()
        while (loopStart.elapsedNow() < timeout && remaining.isNotEmpty()) {
            try {
                action(remaining.removeFirst())
            } catch (e: Throwable) {
                logger.error(e) { "Error encountered in runForEach" }
            }
        }

        return remaining
    }

    private fun <T> sort(
        path: DataClassPath<T, T>,
        setup: SortBuilder<T>.(DataClassPath<T, T>) -> Unit,
    ): List<SortPart<T>> =
        SortBuilder<T>().apply { setup(path) }.build()

    /**
     * Task that queries for pending notifications and dispatches them.
     *
     * Finds notifications whose `sendAt` time is between the last refresh and now,
     * preventing duplicate sends. Paginates through results and automatically
     * relaunches if there are more notifications to process.
     */
    public val refreshNotifications: Task<BasicPager> = path.path("refresh-notifs") bind Task<BasicPager> { startInfo ->
        try {
            val now = now()
            val lastRun = lastRunInfo
                .table()
                .run {
                    findOne(Condition.Always)
                        ?: insertOne(RunInstant(Instant.DISTANT_PAST))
                        ?: throw IllegalStateException("Could not insert RunInstant while refreshing notifications")
                }
                .instant

            lastRunInfo.table().updateOne(
                Condition.Always,
                modification<RunInstant> { it.instant assign now - 10.seconds /*10 seconds in the past, giving some overlap prevents issues with NotificationFrequency.Now*/ }
            )

            val endPage = runFor(timeout, startInfo.page) { currentPage ->
                val pageNotifs = info
                    .table()
                    .find(
                        Condition.andNotNull(
                            additionalSendCondition,
                            Condition.Or(
                                self.email.notNull.shouldBeSentNow(lastRun, now),
                                self.sms.notNull.shouldBeSentNow(lastRun, now),
                                self.push.notNull.shouldBeSentNow(lastRun, now)
                            )
                        ),
                        orderBy = sort(self) {
                            it.user.ascending()     // TODO: This could result in sending two bulked notifications if the page limit cuts user notifications
                            it._id.ascending()
                        },
                        limit = startInfo.pageLimit,
                        skip = startInfo.pageLimit * currentPage
                    )
                    .toList()

                if (pageNotifs.isEmpty()) {
                    logger.debug { "No notifications found after $currentPage pages" }
                    return@runFor null
                } else logger.debug { "${pageNotifs.size} notifications found on page $currentPage" }

                sendNotifications(pageNotifs)

                if (pageNotifs.size < startInfo.pageLimit) null
                else currentPage + 1
            }

            if (endPage != null) {
                launch(BasicPager(endPage, startInfo.pageLimit))
            } else cache().remove(scheduleLockKey)
        } catch (e: Exception) {
            cache().remove(scheduleLockKey)
            throw e
        }
    }

    /**
     * Manually triggers a refresh of pending notifications.
     * Starts from page 0 with a default page size of 200.
     */
    context(_: ServerRuntime)
    public suspend fun refreshNotifications(): Unit = refreshNotifications(BasicPager(0, 200))

    /**
     * Scheduled task that automatically refreshes and sends pending notifications.
     *
     * Runs on the configured [refreshSchedule] (default: every minute). Uses a distributed
     * lock via cache to prevent multiple instances from processing simultaneously.
     */
    public val autoRefreshNotifications: ScheduledTask =
        path.path("refresh-notifs") bind ScheduledTask(refreshSchedule, timeout) {
            val acquiredLock = cache().setIfNotExists(scheduleLockKey, "lock", String.serializer(), timeout * 16)
            if (acquiredLock) refreshNotifications()
        }
}