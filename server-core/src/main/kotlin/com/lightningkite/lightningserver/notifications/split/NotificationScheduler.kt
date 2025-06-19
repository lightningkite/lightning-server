package com.lightningkite.lightningserver.notifications.split

import com.lightningkite.EmailAddress
import com.lightningkite.PhoneNumber
import com.lightningkite.UUID
import com.lightningkite.lightningdb.Condition
import com.lightningkite.lightningdb.Database
import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.IndexSet
import com.lightningkite.lightningdb.ModelPermissions
import com.lightningkite.lightningdb.andNotNull
import com.lightningkite.lightningdb.eq
import com.lightningkite.lightningdb.findOne
import com.lightningkite.lightningdb.getMany
import com.lightningkite.lightningdb.gt
import com.lightningkite.lightningdb.insertOne
import com.lightningkite.lightningdb.inside
import com.lightningkite.lightningdb.lte
import com.lightningkite.lightningdb.modification
import com.lightningkite.lightningdb.not
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.db.ModelInfo
import com.lightningkite.lightningserver.db.ModelRestUpdatesWebsocket
import com.lightningkite.lightningserver.db.ModelSerializationInfo
import com.lightningkite.lightningserver.db.modelInfo
import com.lightningkite.lightningserver.email.Email
import com.lightningkite.lightningserver.email.EmailClient
import com.lightningkite.lightningserver.email.EmailLabeledValue
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.exceptions.exceptionSettings
import com.lightningkite.lightningserver.notifications.Event
import com.lightningkite.lightningserver.notifications.Notification
import com.lightningkite.lightningserver.notifications.NotificationAndroid
import com.lightningkite.lightningserver.notifications.NotificationClient
import com.lightningkite.lightningserver.notifications.NotificationContent
import com.lightningkite.lightningserver.notifications.NotificationData
import com.lightningkite.lightningserver.notifications.NotificationIos
import com.lightningkite.lightningserver.notifications.NotificationSendResult
import com.lightningkite.lightningserver.notifications.NotificationSystemUtils
import com.lightningkite.lightningserver.notifications.NotificationWeb
import com.lightningkite.lightningserver.notifications._id
import com.lightningkite.lightningserver.notifications.email
import com.lightningkite.lightningserver.notifications.inAppOnlySent
import com.lightningkite.lightningserver.notifications.instant
import com.lightningkite.lightningserver.notifications.push
import com.lightningkite.lightningserver.notifications.read
import com.lightningkite.lightningserver.notifications.sendAt
import com.lightningkite.lightningserver.notifications.sent
import com.lightningkite.lightningserver.notifications.sms
import com.lightningkite.lightningserver.notifications.user
import com.lightningkite.lightningserver.schedule.schedule
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.sms.SMSClient
import com.lightningkite.lightningserver.tasks.task
import com.lightningkite.now
import com.lightningkite.serialization.DataClassPath
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
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.forEach
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Serializable
@GenerateDataClassPaths
@IndexSet(["user", "sendAt",])
data class NotificationForUser2<UID, CONTENT : NotificationContent>(
    override val _id: UUID = UUID.random(),
    val event: Event,
    val user: UID,
    val content: CONTENT,
    val createdAt: Instant = now(),
    val read: Instant? = null,
    val email: SendInfo? = null,
    val push: SendInfo? = null,
    val sms: SendInfo? = null,
    val inAppOnlySent: Boolean = false // This is used to notify web sockets that the notification should be sent in-app if no other method is specified TODO: Find a way to remove this, I don't like it
): HasId<UUID>

@Serializable
@GenerateDataClassPaths
data class SendInfo(
    val sendAt: Instant,
    val sent: Boolean = false
)

abstract class NotificationScheduler<USER : HasId<UID>, UID : Comparable<UID>, CONTENT : NotificationContent>(
    val name: String,
    val cache: ()->Cache,
    val database: ()->Database,
    val users: ModelInfo<USER, USER, UID>,
    val contentSerializer: KSerializer<CONTENT>,
    val email: (()->EmailClient)? = null,
    val sms: (()->SMSClient)? = null,
    val push: (()->NotificationClient)? = null,
    val actionTimeoutSeconds: Int = 45,
): EventHandler<USER> {
    abstract suspend fun email(user: USER): EmailAddress?
    abstract suspend fun phone(user: USER): PhoneNumber?
    abstract suspend fun fcmTokens(user: USER): Set<String>
    abstract suspend fun onFcmTokensDead(user: USER, deadTokens: Set<String>)

    abstract val notifications: NotificationInfoAndEndpoints

    open val additionalSendCondition: Condition<NotificationForUser2<UID, CONTENT>>? = null

    /**
     * Generates a list of [Email] to be sent to the user based on the provided notifications.
     *
     * Override this method to customize the email content for bulk notifications.
     *
     * @param user The user to whom the emails will be sent.
     * @param notifications The list of notifications to be included in the emails.
     * @return A list of `Email` to be sent.
     * */
    open suspend fun makeEmailNotifications(user: USER, notifications: List<NotificationForUser2<UID, CONTENT>>): List<Email> {
        val email = email(user)?.let { listOf(EmailLabeledValue(it)) } ?: return emptyList()

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
    open suspend fun makeSmsNotifications(user: USER, notifications: List<NotificationForUser2<UID, CONTENT>>): List<String> = notifications.map {
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
    open suspend fun makePushNotifications(user: USER, notifications: List<NotificationForUser2<UID, CONTENT>>): List<NotificationData> = notifications.map { notif ->
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



    //_____Refreshing and sending notifications_____
    // Notifications are created with a 'sendAt' time, this specifies when the notification should be sent
    // and is how groups of notifications are bulked together. Every minute refreshNotifications queries for
    // unsent notifications whose sendAt time is in the past, and sends them.
    //
    // Notifications with overlapping sendAts are bulked together per user.
    //
    // Checking for queued notifications and sending them is split into two actions: refreshNotifications
    // and sendNotifications.

    private suspend fun sendEmailNotifications(user: USER, notifications: List<NotificationForUser2<UID, CONTENT>>) {
        if (email == null) return
        if (email(user) == null) return

        val emails = makeEmailNotifications(user, notifications)
        if (emails.isNotEmpty()) email.invoke().sendBulk(emails)
    }

    private suspend fun sendSmsNotifications(user: USER, notifications: List<NotificationForUser2<UID, CONTENT>>) {
        if (sms == null) return
        val phoneNumber = phone(user)?.raw ?: return

        val messages = makeSmsNotifications(user, notifications)
        if (messages.isEmpty()) return
        val sms = sms.invoke()
        messages.forEach { sms.send(phoneNumber, it) }
    }

    private suspend fun sendPushNotifications(user: USER, notifications: List<NotificationForUser2<UID, CONTENT>>) {
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
        if(deadTokens.isNotEmpty()) onFcmTokensDead(user, deadTokens)
    }

    @Serializable
    data class BasicPager(
        val page: Int,
        val pageLimit: Int,
    )

    @Serializable
    data class NotificationPager<USER:HasId<UID>, UID:Comparable<UID>, CONTENT:NotificationContent>(
        val users: Map<UID, USER>,
        val notifications: List<NotificationForUser2<UID, CONTENT>>
    )

    // Task to a send list of notifications, pages to make sure all notifications are sent
    val sendNotifications = task(
        "$name.sendNotifications",
        NotificationPager.serializer(users.serialization.serializer, users.serialization.idSerializer, contentSerializer)
    ) { startInfo ->
        val byUser = startInfo.notifications.groupBy { it.user }

        val unsent = NotificationSystemUtils.runForEach(actionTimeoutSeconds, byUser.entries) { (userId, userNotifs) ->
            try {
                val user = startInfo.users[userId] ?: throw NotFoundException("User could not be found to send notifications: $userId")

                supervisorScope {
                    launch {
                        val toEmail = userNotifs.filter { it.email?.sent == false }
                        if (toEmail.isEmpty()) return@launch
                        try {
                            sendEmailNotifications(user, toEmail)
                            notifications.collection().updateManyIgnoringResult(
                                notifications.condition { n -> n._id inside toEmail.map { it._id } },
                                notifications.modification {
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
                            notifications.collection().updateManyIgnoringResult(
                                notifications.condition { n -> n._id inside toSms.map { it._id } },
                                notifications.modification {
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
                            notifications.collection().updateManyIgnoringResult(
                                notifications.condition { n -> n._id inside toPush.map { it._id } },
                                notifications.modification {
                                    it.push.notNull.sent assign true
                                }
                            )
                        } catch (e: Exception) {
                            exceptionSettings().report(e)
                        }
                    }
                    launch {
                        val inAppOnly = userNotifs.filter { it.email == null && it.sms == null && it.push == null }
                        if (inAppOnly.isEmpty()) return@launch
                        try {
                            notifications.collection().updateManyIgnoringResult(
                                notifications.condition { n -> n._id inside inAppOnly.map { it._id } },
                                notifications.modification {
                                    it.inAppOnlySent assign true
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
    suspend fun sendNotifications(notifications: List<NotificationForUser2<UID, CONTENT>>) {
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
                modification { it.instant assign now - 30.seconds /*30 seconds in the past, giving some overlap prevents issues with NotificationFrequency.Now*/ }
            )

            val endPage = NotificationSystemUtils.runFor(actionTimeoutSeconds, startInfo.page) { currentPage ->
                val pageNotifs = notifications
                    .collection()
                    .find(
                        notifications.condition {
                            Condition.andNotNull(
                                additionalSendCondition,
                                Condition.Or(
                                    it.email.notNull.shouldBeSentNow(lastRun, now),
                                    it.sms.notNull.shouldBeSentNow(lastRun, now),
                                    it.push.notNull.shouldBeSentNow(lastRun, now)
                                )
                            )
                        },
                        orderBy = notifications.sort {
                            it.user.ascending()     // TODO: This could result in sending two bulked notifications if the page limit cuts user notifications
                            it._id.ascending()
                        },
                        limit = startInfo.pageLimit,
                        skip = startInfo.pageLimit * currentPage
                    )
                    .toList()

                if (pageNotifs.isEmpty()) {
                    NotificationSystemUtils.logger.debug("No notifications found after $currentPage pages")
                    return@runFor null
                } else NotificationSystemUtils.logger.debug("${pageNotifs.size} notifications found on page $currentPage")

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

    val autoRefreshNotifications = schedule("$name.refreshNotifications", 1.minutes) {
        val acquiredLock = cache().setIfNotExists(scheduleLockKey, "lock", String.serializer(), (actionTimeoutSeconds*16).seconds)       // TODO: I'm not sure if this timeout will remove the item, if it doesn't that breaks this functionality
        if (acquiredLock) refreshNotifications(BasicPager(0, 200))
    }


    open inner class NotificationInfoAndEndpoints(
        path: ServerPath,
        info: ModelInfo<USER, NotificationForUser2<UID, CONTENT>, UUID>
    ) : InfoAndEndpoints<USER, UID, NotificationForUser2<UID, CONTENT>, UUID>(path, info) {
        val websocket = ModelRestUpdatesWebsocket(
            path = restPath,
            info = info,
            key = NotificationForUser2_user(users.serialization.idSerializer, contentSerializer)
        )
    }
}