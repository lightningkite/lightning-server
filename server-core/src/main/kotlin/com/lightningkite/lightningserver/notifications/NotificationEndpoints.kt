package com.lightningkite.lightningserver.notifications

import com.lightningkite.EmailAddress
import com.lightningkite.PhoneNumber
import com.lightningkite.UUID
import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.*
import com.lightningkite.lightningserver.email.Email
import com.lightningkite.lightningserver.email.EmailClient
import com.lightningkite.lightningserver.email.EmailLabeledValue
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.exceptions.exceptionSettings
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.schedule.schedule
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.sms.SMSClient
import com.lightningkite.lightningserver.tasks.task
import com.lightningkite.lightningserver.typed.AuthAccessor
import com.lightningkite.lightningserver.typed.api
import com.lightningkite.now
import com.lightningkite.serialization.DataClassPath
import com.lightningkite.serialization.DataClassPathAccess
import com.lightningkite.serialization.DataClassPathSelf
import com.lightningkite.serialization.SerializableProperty
import com.lightningkite.serialization.serializableProperties
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.datetime.Instant
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.html
import kotlinx.html.p
import kotlinx.html.stream.createHTML
import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

abstract class NotificationEndpoints<USER : HasId<UID>, UID : Comparable<UID>, CONTENT : NotificationContent>(
    val name: String,
    val cache: ()->Cache,
    val database: ()->Database,
    val users: ModelInfo<USER, USER, UID>,
    val contentSerializer: KSerializer<CONTENT>,
    val email: (()->EmailClient)? = null,
    val sms: (()->SMSClient)? = null,
    val push: (()->NotificationClient)? = null,
    val actionTimeoutSeconds: Int = 45,
) {
    abstract suspend fun email(user: USER): EmailAddress?
    abstract suspend fun phone(user: USER): PhoneNumber?
    abstract suspend fun fcmTokens(user: USER): Set<String>
    abstract suspend fun onFcmTokensDead(user: USER, deadTokens: Set<String>)
    abstract val notifications: NotificationInfoAndEndpoints
    abstract val subscriptions: SubscriptionInfoAndEndpoints

    val eventRegistry = FullEventType.Registry<USER, UID, CONTENT>()

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
        val email = email(user)?.let { listOf(EmailLabeledValue(it.raw)) } ?: return emptyList()

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

        val notificationData = makePushNotifications(user, notifications)
        if (notificationData.isEmpty()) return
        val push = push.invoke()
        val allTokens = fcmTokens(user).toMutableSet()
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

    //_____Refreshing and sending notifications_____
    // Notifications are created with a 'sendAt' time, this specifies when the notification should be sent
    // and is how groups of notifications are bulked together. Every minute refreshNotifications queries for
    // unsent notifications whose sendAt time is in the past, and sends them.
    //
    // Notifications with overlapping sendAts are bulked together per user.
    //
    // Checking for queued notifications and sending them is split into two actions: refreshNotifications
    // and sendNotifications.

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

        val unsent = NotificationSystemUtils.runForEach(actionTimeoutSeconds, byUser.entries) { (userId, userNotifs) ->
            try {
                val user = startInfo.users[userId] ?: throw NotFoundException("User could not be found to send notifications: $userId")

                supervisorScope {
                    launch {
                        val toEmail = userNotifs.filter { it.email != null }
                        if (toEmail.isEmpty()) return@launch
                        try {
                            sendEmailNotifications(user, toEmail)
                            notifications.collection().updateManyIgnoringResult(
                                notifications.condition { n -> n._id inside toEmail.map { it._id } },
                                notifications.modification {
                                    it.email assign true
                                }
                            )
                        } catch (e: Exception) {
                            exceptionSettings().report(e)
                        }
                    }
                    launch {
                        val toSms = userNotifs.filter { it.sms != null }
                        if (toSms.isEmpty()) return@launch
                        try {
                            sendSmsNotifications(user, toSms)
                            notifications.collection().updateManyIgnoringResult(
                                notifications.condition { n -> n._id inside toSms.map { it._id } },
                                notifications.modification {
                                    it.sms assign true
                                }
                            )
                        } catch (e: Exception) {
                            exceptionSettings().report(e)
                        }
                    }
                    launch {
                        val toPush = userNotifs.filter { it.push != null }
                        if (toPush.isEmpty()) return@launch
                        try {
                            sendPushNotifications(user, toPush)
                            notifications.collection().updateManyIgnoringResult(
                                notifications.condition { n -> n._id inside toPush.map { it._id } },
                                notifications.modification {
                                    it.push assign true
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
                        notifications.condition { Condition.And(it.sendAt gt lastRun, it.sendAt lte now, !it.sent(), it.read.eq(null)) },
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
        val acquiredLock = cache().setIfNotExists(scheduleLockKey, "lock", String.serializer(), (actionTimeoutSeconds*16).seconds)
        if (acquiredLock) refreshNotifications(BasicPager(0, 200))
    }

    // ___ Events ___
    // When an event happens it gets sent here.
    // Basic flow:
    // - Query for subscriptions for that event type
    // - Check if the filters on the subscription match the event
    // - Create and insert a notification for the event using the subscription information
    //   and event type content generator

    internal suspend fun <T : HasId<ID>, ID : Comparable<ID>> notifyEvent(fullEvent: FullEvent<USER, UID, T, ID, CONTENT>) {
        val event = fullEvent.serialized

        NotificationSystemUtils.logger.debug("Event occurred: ${event.type.name}")

        val subscriptionSerializer = Condition.serializer(fullEvent.type.info.serialization.serializer)

        val subscriptions = subscriptions.collection()
            .find(subscriptions.condition { it._id.type eq event.type })
            .filter {
                try {
                    val subscribedCondition = Condition.And(
                        Serialization.json.decodeFromString(subscriptionSerializer, it.requestedFilter),
                        Serialization.json.decodeFromString(subscriptionSerializer, it.readPermissions)
                    )
                    subscribedCondition(fullEvent.target)
                } catch (e: SerializationException) {
                    // Prevent serialization errors from breaking event
                    exceptionSettings().report(e, "Could not decode event subscription filter. Event Type: $event Subscription: $it")
                    false
                }
            }
            .toList()

        if (subscriptions.isEmpty()) {
            NotificationSystemUtils.logger.debug("No subscriptions found for ${event.type.name}")
            return
        }
        else NotificationSystemUtils.logger.debug("${subscriptions.size} subscriptions found for ${event.type.name}")

        val subscribed = users.collection().getMany(subscriptions.map { it._id.user }).associateBy { it._id }
        val content = fullEvent.type.contentGenerator(fullEvent.target)

        val now = now()

        subscriptions
            .mapNotNull { subscription ->
                val user = subscribed[subscription._id.user] ?: run {
                    // Don't want to prevent other notifications from being created
                    NotificationSystemUtils.logger.error("User ${subscription._id.user} not found when generating notification")
                    return@mapNotNull null
                }
                NotificationForUser(
                    event = event,
                    user = subscription._id.user,
                    content = content(user),
                    sendAt = subscription.frequency.sendAt(now),
                    email = if (subscription.email) false else null,    // Weird syntax, false means will send but not sent yet
                    push = if (subscription.push) false else null,
                    sms = if (subscription.sms) false else null,
                )
            }
            .let { notifications.collection().insert(it) }
    }

    // ___ Handling default subscriptions ___
    // Default subscriptions (defined per event type) depend on users, so when changes happen to users
    // those changes should reflect in default subscriptions.
    //
    // Behavior:
    // - Default subscriptions are always inserted when a new user is created.
    // - Update behavior for a default subscription is specified via [FullEventType.SubscriptionBehavior] tags:
    //      Unspecified (Default) - When a user is updated the default subscription will be regenerated and updated with existing subscription info
    //      REPLACE - When a user is updated the default subscription will replace the existing subscription
    //      IGNORE_USER_CHANGES - User changes are ignored and the default subscription is never regenerated

    init {
        users.registerChangeListener { collectionChanges ->
            val changes = collectionChanges.changes
            val types = eventRegistry.registered

            val created = changes.filter { it.old == null && it.new != null }.mapNotNull { it.new }
            val deleted = changes.filter { it.old != null && it.new == null }.mapNotNull { it.old }
            val changed = changes.filter { it.old != null && it.new != null }.mapNotNull { it.new }

            NotificationSystemUtils.logger.debug("handling default subscriptions - created : ${created.size} deleted : ${deleted.size} changed : ${changed.size}")

            val toInsert = types.flatMap { type ->
                created.mapNotNull { user ->
                    type.defaultSubscription(user)?.toEventSubscription(type, user)
                }
            }

            val toRemove = types.flatMap { type ->
                deleted.map {
                    UserEventType(it._id, type.type)
                }
            }

            val behaviorSpecified = setOf(FullEventType.SubscriptionBehavior.REPLACE, FullEventType.SubscriptionBehavior.IGNORE_USER_CHANGES)
            val toUpdate = HashMap<UserEventType<UID>, EventSubscription<UID>>(types.size*changed.size)
            for (type in types.filter { type -> type.tags.none { it in behaviorSpecified } }) {
                for (user in changed) {
                    type.defaultSubscription(user)?.let {
                        toUpdate[UserEventType(user._id, type.type)] = it.toEventSubscription(type, user)
                    }
                }
            }

            val toReplace = HashMap<UserEventType<UID>, EventSubscription<UID>>(types.size*changed.size)
            for (type in types.filter { FullEventType.SubscriptionBehavior.REPLACE in it.tags }) {
                for (user in changed) {
                    type.defaultSubscription(user)?.let {
                        toReplace[UserEventType(user._id, type.type)] = it.toEventSubscription(type, user)
                    }
                }
            }

            if (toUpdate.isNotEmpty()) subscriptions
                .collection()
                .getMany(toUpdate.keys)
                .forEach { stored ->
                    val default = toUpdate[stored._id] ?: return@forEach
                    val updated = default.copy(
                        frequency = stored.frequency,
                        email = stored.email,
                        sms = stored.sms,
                        push = stored.push
                    )
                    if (updated == stored) toUpdate.remove(stored._id)
                    else toUpdate[stored._id] = updated
                }

            val removeKeys = (toRemove + toUpdate.keys + toReplace.keys).toSet()
            if (removeKeys.isNotEmpty()) subscriptions.collection().deleteManyIgnoringOld(
                subscriptions.condition { it._id inside removeKeys }
            )

            val inserted = toInsert + toUpdate.values + toReplace.values
            if (inserted.isNotEmpty()) subscriptions.collection().insertMany(inserted)
        }
    }


    abstract class InfoAndEndpoints<USER : HasId<UID>, UID : Comparable<UID>, T : HasId<ID>, ID : Comparable<ID>>(
        path: ServerPath,
        val info: ModelInfo<USER, T, ID>
    ) : ModelInfo<USER, T, ID> by info, ServerPathGroup(path) {
        val restPath = path("rest")
        val rest = ModelRestEndpoints(restPath, this)

        private val self = DataClassPathSelf(info.serialization.serializer)

        // These are needed because of a kotlin typing error: "Non-reified type parameters with recursive bounds are not supported yet"
        fun condition(condition: (DataClassPath<T, T>)->Condition<T>) = condition(self)
        fun modification(modification: ModificationBuilder<T>.(DataClassPath<T, T>)->Unit) = ModificationBuilder<T>().apply { modification(self) }.build()
        fun sort(setup: SortBuilder<T>.(DataClassPath<T, T>)->Unit) = SortBuilder<T>().apply { setup(self) }.build()
    }

    open inner class NotificationInfoAndEndpoints(
        path: ServerPath,
        info: ModelInfo<USER, NotificationForUser<UID, CONTENT>, UUID>
    ) : InfoAndEndpoints<USER, UID, NotificationForUser<UID, CONTENT>, UUID>(path, info) {
        val websocket = ModelRestUpdatesWebsocket(
            path = restPath,
            info = info,
            key = NotificationForUser_user(users.serialization.idSerializer, contentSerializer)
        )
    }

    @Suppress("UNCHECKED_CAST")
    open inner class SubscriptionInfoAndEndpoints(
        path: ServerPath,
        info: ModelInfo<USER, EventSubscription<UID>, UserEventType<UID>>
    ) : InfoAndEndpoints<USER, UID, EventSubscription<UID>, UserEventType<UID>>(path, info) {
        val websocket = ModelRestUpdatesWebsocket(
            path = restPath,
            info = info,
            key = EventSubscription__id(users.serialization.idSerializer)
        )

        override suspend fun collection(auth: AuthAccessor<USER>): FieldCollection<EventSubscription<UID>> = super.collection(auth).interceptCreate { subscription ->
            val fullType = try {
                eventRegistry[subscription._id.type]
            } catch (e: ClassCastException) {
                throw BadRequestException("Event type for subscription has incorrect types", cause = e)
            } catch (e: FullEventType.Registry.EventTypeRegistrationException) {
                throw BadRequestException("Improper event type for subscription: $e", cause = e)
            }

            try {
                Serialization.json.decodeFromString(fullType.conditionSerializer, subscription.requestedFilter)
            } catch (e: Exception) {
                throw BadRequestException("Could not decode requested subscription filter for event type: $e", cause = e)
            }

            subscription.copy(
                readPermissions = Serialization.json.encodeToString(fullType.conditionSerializer, fullType.info.permissions(auth).read)
            )
        }

        open val eventAuthOptions: AuthOptions<USER> = info.authOptions
        open fun eventPermissions(auth: AuthAccessor<USER>): ModelPermissions<EventType> = ModelPermissions.allowAll()

        private fun <T> List<T>.sortedWithNullable(comparator: Comparator<T>?): List<T> = if (comparator == null) this else sortedWith(comparator)

        val queryEventTypes = path("events").post.api(
            summary = "Query Event Types",
            description = "Queries for registered event types",
            authOptions = eventAuthOptions,
            inputType = Query.serializer(EventType.serializer()),
            outputType = ListSerializer(EventType.serializer()),
            implementation = { query: Query<EventType> ->
                val permissions = eventPermissions(this)

                eventRegistry
                    .registered
                    .map { it.type }
                    .filter { permissions.read(it) && query.condition(it) }
                    .map(permissions::mask)
                    .sortedWithNullable(query.orderBy.comparator)
                    .drop(query.skip)
                    .take(query.limit)
            }
        )
    }
}

fun <K, UID, C : NotificationContent> DataClassPath<K, NotificationForUser<UID, C>>.sent(): Condition<K> = Condition.Or(email eq true, push eq true, sms eq true, inAppOnlySent eq true)
