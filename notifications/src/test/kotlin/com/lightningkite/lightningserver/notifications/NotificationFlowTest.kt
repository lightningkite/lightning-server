//package com.lightningkite.lightningserver.notifications
//
//import com.lightningkite.lightningserver.auth.require
//import com.lightningkite.lightningserver.definition.builder.ServerBuilder
//import com.lightningkite.lightningserver.notifications.events.EventRegistry
//import com.lightningkite.lightningserver.notifications.events.event
//import com.lightningkite.lightningserver.notifications.subscriptions.*
//import com.lightningkite.lightningserver.runtime.test.test
//import com.lightningkite.lightningserver.settings.setStatic
//import com.lightningkite.lightningserver.typed.ModelInfo
//import com.lightningkite.lightningserver.typed.modelInfo
//import com.lightningkite.lightningserver.typed.sdk.module
//import com.lightningkite.services.cache.Cache
//import com.lightningkite.services.database.*
//import com.lightningkite.services.email.EmailService
//import com.lightningkite.services.email.TestEmailService
//import com.lightningkite.services.sms.SMS
//import com.lightningkite.services.sms.TestSMS
//import kotlinx.coroutines.flow.toList
//import kotlinx.coroutines.runBlocking
//import kotlin.test.Test
//import kotlin.test.assertEquals
//import kotlin.test.assertNotNull
//import kotlin.test.assertTrue
//import kotlin.uuid.Uuid
//
///**
// * End-to-end integration tests for the notification system.
// * Tests complete flows from event trigger to notification delivery
// * across different subscription provider types.
// */
//class NotificationFlowTest {
//
//    // ===== NonCustomizable Flow Tests =====
//
//    private object NonCustomizableServer : ServerBuilder() {
//        val database = setting("database", Database.Settings())
//        val cache = setting("cache", Cache.Settings())
//        val sms = setting("sms", SMS.Settings())
//        val email = setting("email", EmailService.Settings())
//
//        val userInfo: ModelInfo<TestUser, TestUser, Uuid> = database.modelInfo(
//            TestUser.require(),
//            permissions = { ModelPermissions.allowAll() }
//        )
//
//        val modelInfo: ModelInfo<TestUser, TestModel, Uuid> = database.modelInfo(
//            TestUser.require(),
//            permissions = { ModelPermissions.allowAll() }
//        )
//
//        val notifications = path.path("notifications") module Notifications
//
//        object Notifications : NotificationEndpoints<
//            TestUser, Uuid,
//            TestNotificationContent,
//            NotificationBulkDispatcher<TestUser, Uuid, TestNotificationContent>,
//            NonCustomizableSubscriptions<TestUser, Uuid>
//        >(
//            users = userInfo,
//            dispatcher = Dispatcher,
//            subscriptions = NonCustomizableSubscriptions()
//        ) {
//            object Dispatcher : TestDispatcherBase(
//                info = database.testModelInfo(),
//                cache = cache,
//                database = database,
//                users = userInfo,
//                email = email,
//                sms = sms
//            )
//
//            val modelCreated = event("model-created", modelInfo) { notif ->
//                notif.subscribed(
//                    email = Frequency.immediately(),
//                    sms = Frequency.immediately(),
//                    push = null,
//                    inApp = Frequency.immediately()
//                ) { event ->
//                    setOf(event.subject.ownerId)
//                }
//                notif.content { event ->
//                    { user -> TestNotificationContent.MODEL_CREATED }
//                }
//            }
//        }
//    }
//
//    @Test
//    fun `complete flow with NonCustomizable subscriptions`() {
//        var testEmail: TestEmailService? = null
//        var testSms: TestSMS? = null
//
//        NonCustomizableServer.test({ context ->
//            sms setStatic TestSMS("sms", context).also { testSms = it }
//            email setStatic TestEmailService("email", context).also { testEmail = it }
//        }) {
//            runBlocking {
//                // 1. Create user
//                val user = TestUser(name = "E2EUser", email = "e2e@example.com", phone = "1234567890")
//                NonCustomizableServer.userInfo.table().insertOne(user)
//
//                // 2. Trigger event
//                val model = TestModel(name = "E2EModel", ownerId = user._id)
//                NonCustomizableServer.Notifications.modelCreated(model)
//
//                // 3. Verify notification created
//                val notifications = NonCustomizableServer.Notifications.dispatcher.info.table().all().toList()
//                val userNotif = notifications.find { it.user == user._id }
//                assertNotNull(userNotif)
//                assertEquals(TestNotificationContent.MODEL_CREATED, userNotif.content)
//
//                // 4. Verify email sent
//                assertTrue(testEmail!!.sentEmails.isNotEmpty())
//                assertEquals(TestNotificationContent.MODEL_CREATED.title, testEmail!!.sentEmails.last().subject)
//
//                // 5. Verify SMS sent
//                assertTrue(testSms!!.messageHistory.isNotEmpty())
//
//                // 6. Verify channels marked as sent
//                assertTrue(userNotif.email?.sent ?: false)
//                assertTrue(userNotif.sms?.sent ?: false)
//                assertTrue(userNotif.inApp?.sent ?: false)
//            }
//        }
//    }
//
//    // ===== FrequencyCustomizable Flow Tests =====
//
//    private object FrequencyCustomizableServer : ServerBuilder() {
//        val database = setting("database", Database.Settings())
//        val cache = setting("cache", Cache.Settings())
//        val sms = setting("sms", SMS.Settings())
//        val email = setting("email", EmailService.Settings())
//
//        val userInfo: ModelInfo<TestUser, TestUser, Uuid> = database.modelInfo(
//            TestUser.require(),
//            permissions = { ModelPermissions.allowAll() }
//        )
//
//        val modelInfo: ModelInfo<TestUser, TestModel, Uuid> = database.modelInfo(
//            TestUser.require(),
//            permissions = { ModelPermissions.allowAll() }
//        )
//
//        val freqSubsInfo: ModelInfo<TestUser, NotificationSendMethods<Uuid>, UserEventType<Uuid>> = database.modelInfo(
//            TestUser.require(),
//            permissions = { ModelPermissions.allowAll() }
//        )
//
//        val notifications = path.path("notifications") module Notifications
//
//        object Notifications : NotificationEndpoints<
//            TestUser, Uuid,
//            TestNotificationContent,
//            NotificationBulkDispatcher<TestUser, Uuid, TestNotificationContent>,
//            FrequencyCustomizableSubscriptions<TestUser, Uuid>
//        >(
//            users = userInfo,
//            dispatcher = Dispatcher,
//            subscriptions = FrequencyCustomizableSubscriptions(freqSubsInfo, null)
//        ) {
//            object Dispatcher : TestDispatcherBase(
//                info = database.testModelInfo(),
//                cache = cache,
//                database = database,
//                users = userInfo,
//                email = email,
//                sms = sms
//            )
//
//            val modelCreated = event("model-created", modelInfo) { notif ->
//                notif.subscribed(
//                    defaultEmail = Frequency.immediately(),
//                    defaultSms = Frequency.immediately(),
//                    defaultPush = null,
//                    defaultInApp = Frequency.immediately()
//                ) { event ->
//                    setOf(event.subject.ownerId)
//                }
//                notif.content { event ->
//                    { user -> TestNotificationContent.MODEL_CREATED }
//                }
//            }
//        }
//    }
//
//    @Test
//    fun `complete flow with FrequencyCustomizable using defaults`() {
//        var testEmail: TestEmailService? = null
//
//        FrequencyCustomizableServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context).also { testEmail = it }
//        }) {
//            runBlocking {
//                // 1. Create user (no custom preferences)
//                val user = TestUser(name = "FreqDefaultUser", email = "freq@example.com")
//                FrequencyCustomizableServer.userInfo.table().insertOne(user)
//
//                // 2. Trigger event
//                val model = TestModel(name = "FreqModel", ownerId = user._id)
//                FrequencyCustomizableServer.Notifications.modelCreated(model)
//
//                // 3. Verify notification created with defaults
//                val notifications = FrequencyCustomizableServer.Notifications.dispatcher.info.table().all().toList()
//                val userNotif = notifications.find { it.user == user._id }
//                assertNotNull(userNotif)
//
//                // 4. Verify email sent (default is immediate)
//                assertTrue(testEmail!!.sentEmails.isNotEmpty())
//            }
//        }
//    }
//
//    @Test
//    fun `complete flow with FrequencyCustomizable with user override`() {
//        var testEmail: TestEmailService? = null
//
//        FrequencyCustomizableServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context).also { testEmail = it }
//        }) {
//            runBlocking {
//                // 1. Create user
//                val user = TestUser(name = "FreqOverrideUser", email = "override@example.com")
//                FrequencyCustomizableServer.userInfo.table().insertOne(user)
//
//                // 2. Set user preference to disable email
//                FrequencyCustomizableServer.Notifications.subscriptions.info.table().insertOne(
//                    NotificationSendMethods(
//                        _id = UserEventType(user._id, "model-created"),
//                        email = null, // Disable email
//                        sms = Frequency.immediately(),
//                        push = null,
//                        inApp = Frequency.immediately()
//                    )
//                )
//
//                // 3. Trigger event
//                val initialEmailCount = testEmail!!.sentEmails.size
//                val model = TestModel(name = "OverrideModel", ownerId = user._id)
//                FrequencyCustomizableServer.Notifications.modelCreated(model)
//
//                // 4. Verify notification created
//                val notifications = FrequencyCustomizableServer.Notifications.dispatcher.info.table().all().toList()
//                val userNotif = notifications.find { it.user == user._id }
//                assertNotNull(userNotif)
//
//                // 5. Email should be disabled (null in notification)
//                kotlin.test.assertNull(userNotif.email)
//
//                // 6. No additional email should have been sent
//                assertEquals(initialEmailCount, testEmail!!.sentEmails.size)
//            }
//        }
//    }
//
//    // ===== FullyCustomizable Flow Tests =====
//
//    private object FullyCustomizableServer : ServerBuilder() {
//        val database = setting("database", Database.Settings())
//        val cache = setting("cache", Cache.Settings())
//        val sms = setting("sms", SMS.Settings())
//        val email = setting("email", EmailService.Settings())
//
//        val userInfo: ModelInfo<TestUser, TestUser, Uuid> = database.modelInfo(
//            TestUser.require(),
//            permissions = { ModelPermissions.allowAll() }
//        )
//
//        val modelInfo: ModelInfo<TestUser, TestModel, Uuid> = database.modelInfo(
//            TestUser.require(),
//            permissions = { ModelPermissions.allowAll() }
//        )
//
//        val sharedRegistry = EventRegistry<TestUser>()
//
//        val fullSubsInfo: ModelInfo<TestUser, NotificationSubscription<Uuid>, Uuid> = database.modelInfo(
//            TestUser.require(),
//            permissions = { ModelPermissions.allowAll() }
//        )
//
//        val notifications = path.path("notifications") module Notifications
//
//        object Notifications : NotificationEndpoints<
//            TestUser, Uuid,
//            TestNotificationContent,
//            NotificationBulkDispatcher<TestUser, Uuid, TestNotificationContent>,
//            FullyCustomizableSubscriptions<TestUser, Uuid>
//        >(
//            users = userInfo,
//            dispatcher = Dispatcher,
//            subscriptions = FullyCustomizableSubscriptions(
//                info = fullSubsInfo,
//                users = userInfo,
//                principal = TestUser,
//                events = sharedRegistry
//            ),
//            registry = sharedRegistry
//        ) {
//            object Dispatcher : TestDispatcherBase(
//                info = database.testModelInfo(),
//                cache = cache,
//                database = database,
//                users = userInfo,
//                email = email,
//                sms = sms
//            )
//
//            val modelCreated = event("model-created", modelInfo) { notif ->
//                notif.default(
//                    generator = { event -> setOf(event.subject.ownerId) },
//                    email = Frequency.immediately(),
//                    sms = Frequency.immediately(),
//                    push = null,
//                    inApp = Frequency.immediately()
//                )
//                notif.content { event ->
//                    { user -> TestNotificationContent.MODEL_CREATED }
//                }
//            }
//        }
//    }
//
//    @Test
//    fun `complete flow with FullyCustomizable subscriptions`() {
//        var testEmail: TestEmailService? = null
//
//        FullyCustomizableServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context).also { testEmail = it }
//        }) {
//            runBlocking {
//                // 1. Create user - subscriptions auto-created by FullyCustomizable
//                val user = TestUser(name = "FullCustomUser", email = "fullcustom@example.com")
//                FullyCustomizableServer.userInfo.table().insertOne(user)
//
//                // 2. Trigger event
//                val model = TestModel(name = "FullCustomModel", ownerId = user._id)
//                FullyCustomizableServer.Notifications.modelCreated(model)
//
//                // 3. Verify notification created
//                val notifications = FullyCustomizableServer.Notifications.dispatcher.info.table().all().toList()
//                val userNotif = notifications.find { it.user == user._id }
//                assertNotNull(userNotif)
//                assertEquals(TestNotificationContent.MODEL_CREATED, userNotif.content)
//
//                // 4. Verify email sent
//                assertTrue(testEmail!!.sentEmails.isNotEmpty())
//            }
//        }
//    }
//
//    // ===== Event with No Subscribers Tests =====
//
//    private object NoSubscriberServer : ServerBuilder() {
//        val database = setting("database", Database.Settings())
//        val cache = setting("cache", Cache.Settings())
//        val sms = setting("sms", SMS.Settings())
//        val email = setting("email", EmailService.Settings())
//
//        val userInfo: ModelInfo<TestUser, TestUser, Uuid> = database.modelInfo(
//            TestUser.require(),
//            permissions = { ModelPermissions.allowAll() }
//        )
//
//        val modelInfo: ModelInfo<TestUser, TestModel, Uuid> = database.modelInfo(
//            TestUser.require(),
//            permissions = { ModelPermissions.allowAll() }
//        )
//
//        val notifications = path.path("notifications") module Notifications
//
//        object Notifications : NotificationEndpoints<
//            TestUser, Uuid,
//            TestNotificationContent,
//            NotificationBulkDispatcher<TestUser, Uuid, TestNotificationContent>,
//            NonCustomizableSubscriptions<TestUser, Uuid>
//        >(
//            users = userInfo,
//            dispatcher = Dispatcher,
//            subscriptions = NonCustomizableSubscriptions()
//        ) {
//            object Dispatcher : TestDispatcherBase(
//                info = database.testModelInfo(),
//                cache = cache,
//                database = database,
//                users = userInfo,
//                email = email,
//                sms = sms
//            )
//
//            // Event returns empty set - no subscribers
//            val noSubscribers = event("no-subscribers", modelInfo) { notif ->
//                notif.subscribed(
//                    email = Frequency.immediately(),
//                    sms = Frequency.immediately(),
//                    push = null,
//                    inApp = Frequency.immediately()
//                ) { event ->
//                    emptySet() // No one subscribed
//                }
//                notif.content { event ->
//                    { user -> TestNotificationContent.MODEL_CREATED }
//                }
//            }
//        }
//    }
//
//    @Test
//    fun `event with no subscribers creates no notifications`() {
//        var testEmail: TestEmailService? = null
//
//        NoSubscriberServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context).also { testEmail = it }
//        }) {
//            runBlocking {
//                // 1. Create user
//                val user = TestUser(name = "NoSubUser")
//                NoSubscriberServer.userInfo.table().insertOne(user)
//
//                // 2. Trigger event with no subscribers
//                val initialEmailCount = testEmail!!.sentEmails.size
//                val model = TestModel(name = "NoSubModel", ownerId = user._id)
//                NoSubscriberServer.Notifications.noSubscribers(model)
//
//                // 3. No notification should be created for this event
//                val notifications = NoSubscriberServer.Notifications.dispatcher.info.table().all().toList()
//                // Filter to notifications specifically from noSubscribers event
//                val noSubNotifs = notifications.filter {
//                    it.eventData?.eventType == "no-subscribers"
//                }
//                assertTrue(noSubNotifs.isEmpty())
//
//                // 4. No new emails sent
//                assertEquals(initialEmailCount, testEmail!!.sentEmails.size)
//            }
//        }
//    }
//
//    // ===== Content Generation Tests =====
//
//    private object ContentTestServer : ServerBuilder() {
//        val database = setting("database", Database.Settings())
//        val cache = setting("cache", Cache.Settings())
//        val sms = setting("sms", SMS.Settings())
//        val email = setting("email", EmailService.Settings())
//
//        val userInfo: ModelInfo<TestUser, TestUser, Uuid> = database.modelInfo(
//            TestUser.require(),
//            permissions = { ModelPermissions.allowAll() }
//        )
//
//        val modelInfo: ModelInfo<TestUser, TestModel, Uuid> = database.modelInfo(
//            TestUser.require(),
//            permissions = { ModelPermissions.allowAll() }
//        )
//
//        val notifications = path.path("notifications") module Notifications
//
//        object Notifications : NotificationEndpoints<
//            TestUser, Uuid,
//            TestNotificationContent,
//            NotificationBulkDispatcher<TestUser, Uuid, TestNotificationContent>,
//            NonCustomizableSubscriptions<TestUser, Uuid>
//        >(
//            users = userInfo,
//            dispatcher = Dispatcher,
//            subscriptions = NonCustomizableSubscriptions()
//        ) {
//            object Dispatcher : TestDispatcherBase(
//                info = database.testModelInfo(),
//                cache = cache,
//                database = database,
//                users = userInfo,
//                email = email,
//                sms = sms
//            )
//
//            // Different events with different content types
//            val eventA = event("event-a", modelInfo) { notif ->
//                notif.subscribed(
//                    email = Frequency.immediately(),
//                    sms = null,
//                    push = null,
//                    inApp = Frequency.immediately()
//                ) { event ->
//                    setOf(event.subject.ownerId)
//                }
//                notif.content { event ->
//                    { user -> TestNotificationContent.MODEL_CREATED }
//                }
//            }
//
//            val eventB = event("event-b", modelInfo) { notif ->
//                notif.subscribed(
//                    email = Frequency.immediately(),
//                    sms = null,
//                    push = null,
//                    inApp = Frequency.immediately()
//                ) { event ->
//                    setOf(event.subject.ownerId)
//                }
//                notif.content { event ->
//                    { user -> TestNotificationContent.MODEL_DELETED }
//                }
//            }
//        }
//    }
//
//    @Test
//    fun `content generator produces correct content per event type`() {
//        var testEmail: TestEmailService? = null
//
//        ContentTestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context).also { testEmail = it }
//        }) {
//            runBlocking {
//                val user = TestUser(name = "ContentUser", email = "content@example.com")
//                ContentTestServer.userInfo.table().insertOne(user)
//
//                // Trigger event A
//                val modelA = TestModel(name = "ModelA", ownerId = user._id)
//                ContentTestServer.Notifications.eventA(modelA)
//
//                // Verify content for event A
//                val notifsAfterA = ContentTestServer.Notifications.dispatcher.info.table().all().toList()
//                val notifA = notifsAfterA.find { it.content == TestNotificationContent.MODEL_CREATED }
//                assertNotNull(notifA)
//
//                // Trigger event B
//                val modelB = TestModel(name = "ModelB", ownerId = user._id)
//                ContentTestServer.Notifications.eventB(modelB)
//
//                // Verify content for event B
//                val notifsAfterB = ContentTestServer.Notifications.dispatcher.info.table().all().toList()
//                val notifB = notifsAfterB.find { it.content == TestNotificationContent.MODEL_DELETED }
//                assertNotNull(notifB)
//
//                // Verify emails have correct subjects
//                val createdEmail = testEmail!!.sentEmails.find { it.subject == TestNotificationContent.MODEL_CREATED.title }
//                val deletedEmail = testEmail!!.sentEmails.find { it.subject == TestNotificationContent.MODEL_DELETED.title }
//                assertNotNull(createdEmail)
//                assertNotNull(deletedEmail)
//            }
//        }
//    }
//
//    // ===== Multiple Users Same Event Test =====
//
//    @Test
//    fun `multiple users receive notifications for same event trigger`() {
//        var testEmail: TestEmailService? = null
//
//        NonCustomizableServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context).also { testEmail = it }
//        }) {
//            runBlocking {
//                // Create multiple users
//                val user1 = TestUser(name = "User1", email = "user1@example.com")
//                val user2 = TestUser(name = "User2", email = "user2@example.com")
//                NonCustomizableServer.userInfo.table().insertOne(user1)
//                NonCustomizableServer.userInfo.table().insertOne(user2)
//
//                // Trigger events for both users
//                NonCustomizableServer.Notifications.modelCreated(TestModel(name = "M1", ownerId = user1._id))
//                NonCustomizableServer.Notifications.modelCreated(TestModel(name = "M2", ownerId = user2._id))
//
//                // Both users should have notifications
//                val notifications = NonCustomizableServer.Notifications.dispatcher.info.table().all().toList()
//                assertTrue(notifications.any { it.user == user1._id })
//                assertTrue(notifications.any { it.user == user2._id })
//
//                // Both should have received emails
//                assertTrue(testEmail!!.sentEmails.size >= 2)
//            }
//        }
//    }
//
//    // ===== Sequential Events Test =====
//
//    @Test
//    fun `sequential events create separate notifications`() {
//        ContentTestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "SeqUser")
//                ContentTestServer.userInfo.table().insertOne(user)
//
//                // Get initial count
//                val initialCount = ContentTestServer.Notifications.dispatcher.info.table().all().toList()
//                    .count { it.user == user._id }
//
//                // Trigger multiple events
//                ContentTestServer.Notifications.eventA(TestModel(name = "M1", ownerId = user._id))
//                ContentTestServer.Notifications.eventA(TestModel(name = "M2", ownerId = user._id))
//                ContentTestServer.Notifications.eventB(TestModel(name = "M3", ownerId = user._id))
//
//                // Should have 3 more notifications
//                val finalCount = ContentTestServer.Notifications.dispatcher.info.table().all().toList()
//                    .count { it.user == user._id }
//                assertEquals(initialCount + 3, finalCount)
//            }
//        }
//    }
//}
