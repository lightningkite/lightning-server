package com.lightningkite.lightningserver.notifications

import com.lightningkite.EmailAddress
import com.lightningkite.PhoneNumber
import com.lightningkite.UUID
import com.lightningkite.lightningdb.Condition
import com.lightningkite.lightningdb.Database
import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.ModelPermissions
import com.lightningkite.lightningdb.andNotNull
import com.lightningkite.lightningdb.condition
import com.lightningkite.lightningdb.eq
import com.lightningkite.lightningdb.findOne
import com.lightningkite.lightningdb.getMany
import com.lightningkite.lightningdb.gt
import com.lightningkite.lightningdb.insertOne
import com.lightningkite.lightningdb.inside
import com.lightningkite.lightningdb.lte
import com.lightningkite.lightningdb.modification
import com.lightningkite.lightningdb.sort
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.ModelInfo
import com.lightningkite.lightningserver.db.ModelRestEndpoints
import com.lightningkite.lightningserver.db.ModelRestUpdatesWebsocket
import com.lightningkite.lightningserver.db.ModelSerializationInfo
import com.lightningkite.lightningserver.db.modelInfo
import com.lightningkite.lightningserver.email.Email
import com.lightningkite.lightningserver.email.EmailClient
import com.lightningkite.lightningserver.email.EmailLabeledValue
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.exceptions.exceptionSettings
import com.lightningkite.lightningserver.schedule.schedule
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.sms.SMSClient
import com.lightningkite.lightningserver.tasks.task
import com.lightningkite.now
import com.lightningkite.serialization.DataClassPath
import com.lightningkite.serialization.DataClassPathSelf
import com.lightningkite.serialization.notNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.datetime.Instant
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.html
import kotlinx.html.p
import kotlinx.html.stream.createHTML
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.serializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.forEach
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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
abstract class NotificationDispatcher<USER : HasId<UID>, UID : Comparable<UID>, CONTENT : NotificationContent>(
    path: ServerPath,
    val info: ModelInfo<USER, NotificationForUser<UID, CONTENT>, UUID>,
    val cache: ()->Cache,
    val database: ()->Database,
    val users: ModelInfo<USER, USER, UID>,
    contentSerializer: KSerializer<CONTENT>,
    val email: (()->EmailClient)? = null,
    val sms: (()->SMSClient)? = null,
    val push: (()->NotificationClient)? = null,
    val name: String = (path.segments.lastOrNull() as? ServerPath.Segment.Constant)?.value ?: "notifications",
    val actionTimeoutSeconds: Int = 45,
): ServerPathGroup(path) {
    abstract suspend fun email(user: USER): EmailAddress?
    abstract suspend fun phone(user: USER): PhoneNumber?
    abstract suspend fun fcmTokens(user: USER): Set<String>
    abstract suspend fun onFcmTokensDead(user: USER, deadTokens: Set<String>)

    val logger: Logger = LoggerFactory.getLogger("com.lightningkite.lightningserver.notifications.NotificationDispatcher")

    val restPath = path("rest")
    val rest = ModelRestEndpoints(restPath, info)

    val websocket = ModelRestUpdatesWebsocket(
        path = restPath,
        info = info,
        key = NotificationForUser_user(users.serialization.idSerializer, contentSerializer)
    )

    open val additionalSendCondition: Condition<NotificationForUser<UID, CONTENT>>? = null

    /**
     * Generates a list of [Email] to be sent to the user based on the provided notifications.
     *
     * Override this method to customize the email content for bulk notifications.
     *
     * @param user The user to whom the emails will be sent.
     * @param notifications The list of notifications to be included in the emails.
     * @return A list of `Email` to be sent.
     * */
    open suspend fun makeEmailNotifications(user: USER, notifications: List<NotificationForUser<UID, CONTENT>>): List<Email> {
        val email = email(user)?.let { listOf(EmailLabeledValue(it.toString())) } ?: return emptyList()

        return notifications.map { notif ->
            Email(
                to = email,
                subject = notif.content.title,
                html = createHTML().html {
                    body {
                        p { +notif.content.body }
                        notif.content.url?.let {
                            a(href = it) { +"Go to page" }
                        }
                    }
                }
            )
        }
    }

    /**
     * Generates a list of SMS messages to be sent to the user based on the provided notifications.
     *
     * Override this method to customize the SMS content for bulk notification
     *
     * @param user The user to whom the SMS messages will be sent.
     * @param notifications The list of notifications to be included in the SMS messages.
     * @return A list of SMS messages as strings to be sent.
     * */
    open suspend fun makeSmsNotifications(user: USER, notifications: List<NotificationForUser<UID, CONTENT>>): List<String> = notifications.map {
        buildString {
            appendLine(it.content.title)
            appendLine(it.content.body)
            if (it.content.url != null) appendLine("Link: ${it.content.url}")
        }
    }

    /**
     * Creates a list of push notifications to be sent to the user based on the provided notifications.
     *
     * Override this method to customize the content of push notifications for bulk delivery.
     *
     * @param user The user who will receive the push notifications.
     * @param notifications The list of notifications to include in the push notifications.
     * @return A list of `NotificationData` objects representing the push notifications to be sent.
     */
    open suspend fun makePushNotifications(user: USER, notifications: List<NotificationForUser<UID, CONTENT>>): List<NotificationData> = notifications.map { notif ->
        NotificationData(
            notification = Notification(    // TODO: Change this class name when moved to LightningServer
                title = notif.content.title,
                body = notif.content.body,
                link = notif.content.url
            ),
            data = mapOf("event" to Serialization.json.encodeToString(Event.serializer(), notif.event)),
            android = NotificationAndroid(),
            ios = NotificationIos(),
            web = NotificationWeb()
        )
    }

    private val self = DataClassPathSelf(info.serialization.serializer)

    //_____Refreshing and sending notifications_____
    // Notifications are created with a 'sendAt' time, this specifies when the notification should be sent
    // and is how groups of notifications are bulked together. Every minute refreshNotifications queries for
    // unsent notifications whose sendAt time is in the past, and sends them.
    //
    // Notifications with overlapping sendAts are bulked together per user.
    //
    // Checking for queued notifications and sending them is split into two actions: refreshNotifications
    // and sendNotifications.

    private suspend fun sendEmailNotifications(user: USER, notifications: List<NotificationForUser<UID, CONTENT>>) {
        if (email == null) return
        if (email(user) == null) return

        val emails = makeEmailNotifications(user, notifications)
        if (emails.isNotEmpty()) email.invoke().sendBulk(emails)
    }

    private suspend fun sendSmsNotifications(user: USER, notifications: List<NotificationForUser<UID, CONTENT>>) {
        if (sms == null) return
        val phoneNumber = phone(user)?.raw ?: return

        val messages = makeSmsNotifications(user, notifications)
        if (messages.isEmpty()) return
        val sms = sms.invoke()
        messages.forEach { sms.send(phoneNumber, it) }
    }

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
            r.forEach { a, b ->
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
    data class BasicPager(
        val page: Int,
        val pageLimit: Int,
    )

    @Serializable
    data class NotificationPager<USER:HasId<UID>, UID:Comparable<UID>, CONTENT:NotificationContent>(
        val users: Map<UID, USER>,
        val notifications: List<NotificationForUser<UID, CONTENT>>
    )

    // Task to a send list of notifications, pages to make sure all notifications are sent
    val sendNotifications = task(
        "$name.sendNotifications",
        NotificationPager.serializer(users.serialization.serializer, users.serialization.idSerializer, contentSerializer)
    ) { startInfo ->
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
                            info.collection().updateManyIgnoringResult(
                                self.condition { n -> n._id inside toEmail.map { it._id } },
                                self.modification {
                                    it.email.notNull.sent assign true
                                }
                            )
                        } catch (e: Exception) {
                            exceptionSettings().report(e)
                        }
                    }
                    launch {
                        val toSms = userNotifs.filter { it.sms?.sent == false }
                        if (toSms.isEmpty()) return@launch
                        try {
                            sendSmsNotifications(user, toSms)
                            info.collection().updateManyIgnoringResult(
                                self.condition { n -> n._id inside toSms.map { it._id } },
                                self.modification {
                                    it.sms.notNull.sent assign true
                                }
                            )
                        } catch (e: Exception) {
                            exceptionSettings().report(e)
                        }
                    }
                    launch {
                        val toPush = userNotifs.filter { it.push?.sent == false}
                        if (toPush.isEmpty()) return@launch
                        try {
                            sendPushNotifications(user, toPush)
                            info.collection().updateManyIgnoringResult(
                                self.condition { n -> n._id inside toPush.map { it._id } },
                                self.modification {
                                    it.push.notNull.sent assign true
                                }
                            )
                        } catch (e: Exception) {
                            exceptionSettings().report(e)
                        }
                    }
                }
            } catch (e: Exception) {
                exceptionSettings().report(e, "Exception occurred in $name.sendNotifications for user $userId")
            }
        }

        if (unsent.isNotEmpty()) restart(NotificationPager(startInfo.users, unsent.flatMap { it.value }))
    }

    /**Gets the users of the notifications and launches a `sendNotifications` task*/
    suspend fun sendNotifications(notifications: List<NotificationForUser<UID, CONTENT>>) {
        val users = users.collection()
            .getMany(notifications.map { it.user })
            .associateBy { it._id }

        sendNotifications(NotificationPager(users, notifications))
    }

    @Serializable
    @GenerateDataClassPaths
    data class RunInstant(val instant: Instant) : HasId<String> {
        companion object{
            const val ID: String = "SINGLETON"
        }
        @Transient
        override val _id: String = ID
    }
    private val lastRunInfo = database.modelInfo(
        authOptions = noAuth,
        serialization = ModelSerializationInfo(RunInstant.serializer(), String.serializer()),
        permissions = { ModelPermissions() }
    )

    // Finds notifications that need to be sent.
    // Importantly, this does not look for all notifications with sendAt times in the past,
    // only sendAt times between the last time checked and now. This means that if notifications
    // attempted to send and failed, sending won't be attempted a second time.
    private val scheduleLockKey = "$name.autoRefreshNotifications_LockKey"

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
                exceptionSettings().report(e, "Exception encountered in runForEach")
            }
        }

        return remaining
    }
    
    val refreshNotifications = task<BasicPager>("$name.refreshNotifications") { startInfo ->
        try {
            val now = now()
            val lastRun = lastRunInfo
                .collection()
                .run {
                    findOne(Condition.Always)
                        ?: insertOne(RunInstant(Instant.DISTANT_PAST))
                        ?: throw IllegalStateException("Could not insert RunInstant while refreshing notifications")
                }
                .instant

            lastRunInfo.collection().updateOne(
                Condition.Always,
                modification<RunInstant> { it.instant assign now - 30.seconds /*30 seconds in the past, giving some overlap prevents issues with NotificationFrequency.Now*/ }
            )

            val endPage = runFor(actionTimeoutSeconds, startInfo.page) { currentPage ->
                val pageNotifs = info
                    .collection()
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
                restart(BasicPager(endPage, startInfo.pageLimit))
            }
            else cache().remove(scheduleLockKey)
        } catch (e:Exception) {
            cache().remove(scheduleLockKey)
            throw e
        }
    }

    suspend fun refreshNotifications() = refreshNotifications(BasicPager(0, 200))

    val autoRefreshNotifications = schedule("$name.refreshNotifications", 1.minutes) {
        val acquiredLock = cache().setIfNotExists(scheduleLockKey, "lock", String.serializer(), (actionTimeoutSeconds*16).seconds)       // TODO: I'm not sure if this timeout will remove the item, if it doesn't that breaks this functionality
        if (acquiredLock) refreshNotifications()
    }
}