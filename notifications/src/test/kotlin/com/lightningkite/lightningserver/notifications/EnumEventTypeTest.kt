package com.lightningkite.lightningserver.notifications

import com.lightningkite.lightningserver.auth.testAuth
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.notifications.events.EventType
import com.lightningkite.lightningserver.notifications.events.EventTypeLike
import com.lightningkite.lightningserver.notifications.events.UserEventType
import com.lightningkite.lightningserver.notifications.events.event
import com.lightningkite.lightningserver.notifications.subscriptions.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.execute
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.settings.setStatic
import com.lightningkite.lightningserver.auth.GrantedScope
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.lightningserver.typed.test as typedTest
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import com.lightningkite.services.email.*
import com.lightningkite.services.notifications.NotificationData
import com.lightningkite.services.sms.SMS
import com.lightningkite.services.sms.TestSMS
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.*
import kotlin.uuid.Uuid

/** An application-defined event type: the notification system works with any [EventTypeLike]. */
@Serializable
enum class AppEvent : EventTypeLike {
    ModelCreated;

    override val eventName: EventType.Name get() = EventType.Name(name)
}

private abstract class EnumDispatcher(
    info: ModelInfo<*, Notification<AppEvent, Uuid, String>, Uuid>,
    cache: Runtime<Cache>,
    database: Runtime<Database>,
    users: ModelInfo<*, TestUser, Uuid>,
    email: Runtime<EmailService>,
    sms: Runtime<SMS>,
) : NotificationBulkDispatcher<AppEvent, TestUser, Uuid, String>(
    info = info,
    cache = cache,
    database = database,
    users = users,
    email = email,
    sms = sms,
    eventSerializer = AppEvent.serializer(),
    contentSerializer = String.serializer(),
) {
    context(server: ServerRuntime)
    override suspend fun email(user: TestUser): EmailAddress = user.email.toEmailAddress()

    context(server: ServerRuntime)
    override suspend fun phone(user: TestUser): PhoneNumber = user.phone.toPhoneNumber()

    context(server: ServerRuntime)
    override suspend fun fcmTokens(user: TestUser): Set<String> = emptySet()

    context(server: ServerRuntime)
    override suspend fun onFcmTokensDead(user: TestUser, deadTokens: Set<String>) {}

    context(runtime: ServerRuntime)
    override suspend fun makeEmailNotifications(
        user: TestUser,
        notifications: List<Notification<AppEvent, Uuid, String>>,
    ): List<Email> = notifications.map {
        Email(subject = it.content, to = listOf(EmailAddressWithName(user.email)), plainText = it.content)
    }

    context(runtime: ServerRuntime)
    override suspend fun makeSmsNotifications(
        user: TestUser,
        notifications: List<Notification<AppEvent, Uuid, String>>,
    ): List<String> = notifications.map { it.content }

    context(runtime: ServerRuntime)
    override suspend fun makePushNotifications(
        user: TestUser,
        notifications: List<Notification<AppEvent, Uuid, String>>,
    ): List<NotificationData> = emptyList()
}

class EnumEventTypeTest {

    // ===== NonCustomizable =====

    private object NonCustomServer : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val cache = setting("cache", Cache.Settings())
        val sms = setting("sms", SMS.Settings())
        val email = setting("email", EmailService.Settings())

        val userInfo = database.testModelInfo<TestUser, Uuid>()
        val modelInfo = database.testModelInfo<TestModel, Uuid>()

        val notifications = path.path("notifications") include NonCustomNotifications
    }

    private object NonCustomNotifications : ServerBuilder() {
        object Dispatcher : EnumDispatcher(
            NonCustomServer.database.testModelInfo(),
            NonCustomServer.cache,
            NonCustomServer.database,
            NonCustomServer.userInfo,
            NonCustomServer.email,
            NonCustomServer.sms,
        )

        val handler = path include NotificationEndpoints(
            eventSerializer = AppEvent.serializer(),
            users = NonCustomServer.userInfo,
            dispatcher = Dispatcher,
            subscriptions = NonCustomizableSubscriptions(),
        )

        val modelCreated = handler.event(AppEvent.ModelCreated, NonCustomServer.modelInfo) { type ->
            type.subscribed { event -> setOf(event.subject.ownerId) }
            type.content { event -> { _ -> "Created: ${event.subject.name}" } }
        }
    }

    @Test
    fun nonCustomizableFlowStoresEnum() {
        NonCustomServer.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context)
        }) {
            runBlocking {
                val user = TestUser()
                execute {
                    NonCustomServer.userInfo.table().insertOne(user)
                    NonCustomNotifications.modelCreated(TestModel(name = "M", ownerId = user._id))
                }

                val stored = execute { NonCustomNotifications.Dispatcher.info.table().all().toList() }.single()
                assertEquals(AppEvent.ModelCreated, stored.event.type)
                assertEquals("Created: M", stored.content)
            }
        }
    }

    // ===== FrequencyCustomizable =====

    private object FreqServer : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val cache = setting("cache", Cache.Settings())
        val sms = setting("sms", SMS.Settings())
        val email = setting("email", EmailService.Settings())

        val userInfo = database.testModelInfo<TestUser, Uuid>()
        val modelInfo = database.testModelInfo<TestModel, Uuid>()

        val notifications = path.path("notifications") include FreqNotifications
    }

    private object FreqNotifications : ServerBuilder() {
        object Dispatcher : EnumDispatcher(
            FreqServer.database.testModelInfo(),
            FreqServer.cache,
            FreqServer.database,
            FreqServer.userInfo,
            FreqServer.email,
            FreqServer.sms,
        )

        val handler = path include NotificationEndpoints(
            eventSerializer = AppEvent.serializer(),
            users = FreqServer.userInfo,
            dispatcher = Dispatcher,
            subscriptions = FrequencyCustomizableSubscriptions<TestUser, Uuid>(
                info = FreqServer.database.testModelInfo(),
            ),
        )

        val modelCreated = handler.event(AppEvent.ModelCreated, FreqServer.modelInfo) { type ->
            type.subscribed { event -> setOf(event.subject.ownerId) }
            type.content { event -> { _ -> "Created: ${event.subject.name}" } }
        }
    }

    @Test
    fun frequencyCustomizableFlowStoresEnum() {
        FreqServer.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context)
        }) {
            runBlocking {
                val user = TestUser()
                execute {
                    FreqServer.userInfo.table().insertOne(user)
                    FreqNotifications.modelCreated(TestModel(name = "M", ownerId = user._id))
                }

                val stored = execute { FreqNotifications.Dispatcher.info.table().all().toList() }.single()
                assertEquals(AppEvent.ModelCreated, stored.event.type)
                assertEquals("Created: M", stored.content)
            }
        }
    }

    @Test
    fun eventEndpointQueryReturnsEnumValues() {
        FreqServer.test(settings = {}) {
            val user = TestUser()
            val types = runBlocking {
                FreqNotifications.handler.eventEndpoints.queryEventTypes.typedTest(TestUser.testAuth(user, scopes = setOf(GrantedScope.root)), Query())
            }
            assertEquals(listOf(AppEvent.ModelCreated), types)
        }
    }

    // ===== FullyCustomizable =====

    private object FullServer : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val cache = setting("cache", Cache.Settings())
        val sms = setting("sms", SMS.Settings())
        val email = setting("email", EmailService.Settings())

        val userInfo = database.testModelInfo<TestUser, Uuid>()
        val modelInfo = database.testModelInfo<TestModel, Uuid>()

        val notifications = path.path("notifications") include FullNotifications
    }

    private object FullNotifications : ServerBuilder() {
        object Dispatcher : EnumDispatcher(
            FullServer.database.testModelInfo(),
            FullServer.cache,
            FullServer.database,
            FullServer.userInfo,
            FullServer.email,
            FullServer.sms,
        )

        val subs = FullyCustomizableSubscriptionsWithAuth(
            info = FullServer.database.testModelInfo<NotificationEventSubscription<Uuid>, UserEventType<Uuid>>(),
            users = FullServer.userInfo,
            principal = TestUser,
            suppressRejectedAuthenticationWarnings = true,
        )

        val handler = path include NotificationEndpoints(
            eventSerializer = AppEvent.serializer(),
            users = FullServer.userInfo,
            dispatcher = Dispatcher,
            subscriptions = subs,
        )

        val modelCreated = handler.event(AppEvent.ModelCreated, FullServer.modelInfo) { type ->
            type.defaultSubscription(
                behavior = DefaultSubscriptionUpdateBehavior.UpdateReadPermissions
            ) { _ ->
                FullEventSubscription(
                    filter = Condition.Always,
                    email = Frequency.immediately(),
                    push = null,
                    sms = Frequency.immediately(),
                    inApp = Frequency.immediately(),
                )
            }
            type.content { event -> { _ -> "Created: ${event.subject.name}" } }
        }
    }

    @Test
    fun fullyCustomizableCreatesDefaultSubscriptionKeyedByEnumName() {
        FullServer.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context)
        }) {
            runBlocking {
                val user = TestUser()
                execute {
                    FullServer.userInfo.table().insertOne(user)
                    FullNotifications.modelCreated(TestModel(name = "M", ownerId = user._id))
                }

                val key = UserEventType(user._id, AppEvent.ModelCreated.eventName)
                assertNotNull(execute { FullNotifications.subs.info.table().get(key) })

                val stored = execute { FullNotifications.Dispatcher.info.table().all().toList() }.single()
                assertEquals(AppEvent.ModelCreated, stored.event.type)
            }
        }
    }
}
