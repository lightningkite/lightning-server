package com.lightningkite.lightningserver.notifications

import com.lightningkite.EmailAddress
import com.lightningkite.PhoneNumber
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.ScheduledTask
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.launch
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.invoke
import com.lightningkite.lightningserver.runtime.location
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.lightningserver.typed.ModelRestEndpoints
import com.lightningkite.lightningserver.typed.ModelRestEndpointsAndUpdatesWebsocket
import com.lightningkite.lightningserver.typed.ModelRestEndpointsAndUpdatesWebsocket.Companion.plus
import com.lightningkite.lightningserver.typed.ModelRestUpdatesWebsocket
import com.lightningkite.lightningserver.typed.explicitModelInfo
import com.lightningkite.lightningserver.typed.modelInfo
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.DataClassPath
import com.lightningkite.services.database.DataClassPathSelf
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.ModelPermissions
import com.lightningkite.services.database.andNotNull
import com.lightningkite.services.database.condition
import com.lightningkite.services.database.findOne
import com.lightningkite.services.database.getMany
import com.lightningkite.services.database.insertOne
import com.lightningkite.services.database.modification
import com.lightningkite.services.email.Email
import com.lightningkite.services.email.EmailService
import com.lightningkite.services.notifications.NotificationData
import com.lightningkite.services.notifications.NotificationSendResult
import com.lightningkite.services.notifications.NotificationService
import com.lightningkite.services.sms.SMS
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.serializer
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.uuid.Uuid


/**
 * `NotificationDispatcher` is in charge of queuing, bulking, formatting, and sending notifications.
 *
 * ## Overview
 * When a [NotificationForUser] is created, it specifies when the notification should be sent via email, sms, and/or push notification.
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
public abstract class NotificationDispatcher<USER : HasId<UID>, UID : Comparable<UID>, CONTENT>(
    public val info: ModelInfo<USER, NotificationForUser<UID, CONTENT>, Uuid>,
    public val cache: Runtime<Cache>,
    public val database: Runtime<Database>,
    public val users: ModelInfo<USER, USER, UID>,
    contentSerializer: KSerializer<CONTENT>,
    public val email: (Runtime<EmailService>)? = null,
    public val sms: (Runtime<SMS>)? = null,
    public val push: (Runtime<NotificationService>)? = null,
    public val actionTimeoutSeconds: Int = 45,
): ServerBuilder() {
    public abstract suspend fun email(user: USER): EmailAddress?
    public abstract suspend fun phone(user: USER): PhoneNumber?
    public abstract suspend fun fcmTokens(user: USER): Set<String>
    public abstract suspend fun onFcmTokensDead(user: USER, deadTokens: Set<String>)

    internal val logger: KLogger = KotlinLogging.logger("com.lightningkite.lightningserver.notifications.NotificationDispatcher")

    public val rest: ModelRestEndpointsAndUpdatesWebsocket<USER, NotificationForUser<UID, CONTENT>, Uuid> =
        path.path("rest") include ModelRestEndpoints(info) + ModelRestUpdatesWebsocket(info)


    protected open val additionalSendCondition: Condition<NotificationForUser<UID, CONTENT>>? = null

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
    public abstract suspend fun makeEmailNotifications(user: USER, notifications: List<NotificationForUser<UID, CONTENT>>): List<Email>

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
    public abstract suspend fun makeSmsNotifications(user: USER, notifications: List<NotificationForUser<UID, CONTENT>>): List<String>

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
    public abstract suspend fun makePushNotifications(user: USER, notifications: List<NotificationForUser<UID, CONTENT>>): List<NotificationData>

    private val self = DataClassPathSelf(info.serializer)

    //_____Refreshing and sending notifications_____
    // Notifications are created with a 'sendAt' time, this specifies when the notification should be sent
    // and is how groups of notifications are bulked together. Every minute refreshNotifications queries for
    // unsent notifications whose sendAt time is in the past, and sends them.
    //
    // Notifications with overlapping sendAts are bulked together per user.
    //
    // Checking for queued notifications and sending them is split into two actions: refreshNotifications
    // and sendNotifications.
    context(runtime: ServerRuntime)
    private suspend fun sendEmailNotifications(user: USER, notifications: List<NotificationForUser<UID, CONTENT>>) {
        if (email == null) return
        if (email(user) == null) return

        val emails = makeEmailNotifications(user, notifications)
        if (emails.isNotEmpty()) email.invoke().sendBulk(emails)
    }

    context(runtime: ServerRuntime)
    private suspend fun sendSmsNotifications(user: USER, notifications: List<NotificationForUser<UID, CONTENT>>) {
        if (sms == null) return
        val phoneNumber = phone(user) ?: return

        val messages = makeSmsNotifications(user, notifications)
        if (messages.isEmpty()) return
        val sms = sms.invoke()
        messages.forEach { sms.send(phoneNumber, it) }
    }

    context(runtime: ServerRuntime)
    private suspend fun sendPushNotifications(user: USER, notifications: List<NotificationForUser<UID, CONTENT>>) {
        if (push == null) return
        val allTokens = fcmTokens(user).toMutableSet()
        if (allTokens.isEmpty()) return

        val notificationData = makePushNotifications(user, notifications)
        if (notificationData.isEmpty()) return

        val push = push.invoke()
        val deadTokens = HashSet<String>()
        notificationData.forEach {
            val r = push.send(allTokens.toList(), it)
            r.forEach { (a, b) ->
                when(b) {
                    NotificationSendResult.DeadToken -> deadTokens.add(a)
                    NotificationSendResult.Failure -> {}
                    NotificationSendResult.Success -> {}
                }
            }
        }
        if (deadTokens.isNotEmpty()) onFcmTokensDead(user, deadTokens)
    }

    @Serializable
    public data class BasicPager(
        val page: Int,
        val pageLimit: Int,
    )

    @Serializable
    public data class NotificationPager<USER:HasId<UID>, UID:Comparable<UID>, CONTENT>(
        val users: Map<UID, USER>,
        val notifications: List<NotificationForUser<UID, CONTENT>>
    )

    // Task to a send list of notifications, pages to make sure all notifications are sent
    public val sendNotifications: Task<NotificationPager<USER, UID, CONTENT>> =
        path.path("send-notifs") bind Task(NotificationPager.serializer(users.serializer, users.idSerializer, contentSerializer)) { startInfo ->
            val byUser = startInfo.notifications.groupBy { it.user }

            val unsent = runForEach(actionTimeoutSeconds, byUser.entries) { (userId, userNotifs) ->
                try {
                    val user = startInfo.users[userId] ?: throw NotFoundException("User could not be found to send notifications: $userId")

                    supervisorScope {
                        launch {
                            val toEmail = userNotifs.filter { it.email?.sent == false }
                            if (toEmail.isEmpty()) return@launch
                            try {
                                sendEmailNotifications(user, toEmail)
                                info.table().updateManyIgnoringResult(
                                    self.condition { n -> n._id inside toEmail.map { it._id } },
                                    modification(self) {
                                        it.email.notNull.sent assign true
                                    }
                                )
                            } catch (e: Exception) {
                                logger.error(e) { "Failed to send email notifications" }
                            }
                        }
                        launch {
                            val toSms = userNotifs.filter { it.sms?.sent == false }
                            if (toSms.isEmpty()) return@launch
                            try {
                                sendSmsNotifications(user, toSms)
                                info.table().updateManyIgnoringResult(
                                    self.condition { n -> n._id inside toSms.map { it._id } },
                                    modification(self) {
                                        it.sms.notNull.sent assign true
                                    }
                                )
                            } catch (e: Exception) {
                                logger.error(e) { "Failed to send sms notifications" }
                            }
                        }
                        launch {
                            val toPush = userNotifs.filter { it.push?.sent == false}
                            if (toPush.isEmpty()) return@launch
                            try {
                                sendPushNotifications(user, toPush)
                                info.table().updateManyIgnoringResult(
                                    self.condition { n -> n._id inside toPush.map { it._id } },
                                    modification(self) {
                                        it.push.notNull.sent assign true
                                    }
                                )
                            } catch (e: Exception) {
                                logger.error(e) { "Failed to send push notifications" }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Exception occurred in $location for user $userId" }
                }
            }

            if (unsent.isNotEmpty()) launch(NotificationPager(startInfo.users, unsent.flatMap { it.value }))
        }

    /**Gets the users of the notifications and launches a `sendNotifications` task*/
    context(_: ServerRuntime)
    public suspend fun sendNotifications(notifications: List<NotificationForUser<UID, CONTENT>>) {
        val users = users.table()
            .getMany(notifications.map { it.user })
            .associateBy { it._id }

        sendNotifications(NotificationPager(users, notifications))
    }

    @Serializable
    @GenerateDataClassPaths
    public data class RunInstant(val instant: Instant) : HasId<String> {
        public companion object{
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
    private val scheduleLockKey = "$${TODO()}.autoRefreshNotifications_LockKey"

    private fun <K> DataClassPath<K, SendInfo>.shouldBeSentNow(lower: Instant, upper: Instant) = Condition.And(sent eq false, sendAt gt lower, sendAt lte upper)

    private suspend fun <T> runFor(seconds: Int, startingValue: T, action: suspend (T) -> T?):T?{
        val loopStart = TimeSource.Monotonic.markNow()
        val duration = seconds.seconds

        var value = startingValue

        while (loopStart.elapsedNow() < duration) {
            value = action(value) ?: return null
        }

        return value
    }

    private suspend fun <T> runForEach(seconds: Int, items: Collection<T>, action: suspend (T)->Unit): List<T> {
        val loopStart = TimeSource.Monotonic.markNow()
        val duration = seconds.seconds

        val remaining = items.toMutableList()
        while (loopStart.elapsedNow() < duration && remaining.isNotEmpty()) {
            try {
                action(remaining.removeFirst())
            }
            catch (e: Throwable) {
                logger.error(e) { "Error encountered in runForEach" }
            }
        }

        return remaining
    }
    
    public val refreshNotifications = path.path("refresh-notifs") bind Task<BasicPager> { startInfo ->
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
                modification<RunInstant> { it.instant assign now - 30.seconds /*30 seconds in the past, giving some overlap prevents issues with NotificationFrequency.Now*/ }
            )

            val endPage = runFor(actionTimeoutSeconds, startInfo.page) { currentPage ->
                val pageNotifs = info
                    .table()
                    .find(
                        self.condition {
                            Condition.andNotNull(
                                additionalSendCondition,
                                Condition.Or(
                                    it.email.notNull.shouldBeSentNow(lastRun, now),
                                    it.sms.notNull.shouldBeSentNow(lastRun, now),
                                    it.push.notNull.shouldBeSentNow(lastRun, now)
                                )
                            )
                        },
                        orderBy = self.sort {
                            it.user.ascending()     // TODO: This could result in sending two bulked notifications if the page limit cuts user notifications
                            it._id.ascending()
                        },
                        limit = startInfo.pageLimit,
                        skip = startInfo.pageLimit * currentPage
                    )
                    .toList()

                if (pageNotifs.isEmpty()) {
                    logger.debug("No notifications found after $currentPage pages")
                    return@runFor null
                } else logger.debug("${pageNotifs.size} notifications found on page $currentPage")

                sendNotifications(pageNotifs)

                if (pageNotifs.size < startInfo.pageLimit) null
                else currentPage + 1
            }

            if (endPage != null) {
                launch(BasicPager(endPage, startInfo.pageLimit))
            }
            else cache().remove(scheduleLockKey)
        } catch (e:Exception) {
            cache().remove(scheduleLockKey)
            throw e
        }
    }

    context(_: ServerRuntime)
    public suspend fun refreshNotifications(): Unit = refreshNotifications(BasicPager(0, 200))

    public val autoRefreshNotifications: ScheduledTask =
        path.path("refresh-notifs") bind ScheduledTask(1.minutes) {
            val acquiredLock = cache().setIfNotExists(scheduleLockKey, "lock", String.serializer(), (actionTimeoutSeconds*16).seconds)       // TODO: I'm not sure if this timeout will remove the item, if it doesn't that breaks this functionality
            if (acquiredLock) refreshNotifications()
        }
}