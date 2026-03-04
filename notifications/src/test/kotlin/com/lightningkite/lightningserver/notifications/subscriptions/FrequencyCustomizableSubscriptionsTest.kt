//package com.lightningkite.lightningserver.notifications.subscriptions
//
//import com.lightningkite.lightningserver.auth.require
//import com.lightningkite.lightningserver.definition.builder.ServerBuilder
//import com.lightningkite.lightningserver.notifications.*
//import com.lightningkite.lightningserver.notifications.events.Event
//import com.lightningkite.lightningserver.notifications.events.UserEventType
//import com.lightningkite.lightningserver.notifications.events.event
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
//import kotlinx.coroutines.runBlocking
//import kotlin.test.Test
//import kotlin.test.assertEquals
//import kotlin.test.assertNull
//import kotlin.uuid.Uuid
//
///**
// * Tests for [FrequencyCustomizableSubscriptions].
// * Verifies frequency-only customization works correctly.
// *
// * Uses owner-based events for test isolation.
// */
//class FrequencyCustomizableSubscriptionsTest {
//
//    // ===== Test Server Definition =====
//
//    private object TestServer : ServerBuilder() {
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
//            FrequencyCustomizableSubscriptions<TestUser, Uuid>
//        >(
//            users = userInfo,
//            dispatcher = Dispatcher,
//            subscriptions = FrequencyCustomizableSubscriptions(
//                info = database.modelInfo(
//                    TestUser.require(),
//                    permissions = { ModelPermissions.allowAll() }
//                )
//            )
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
//            // Event that notifies model owner only - for isolated testing
//            val modelCreated = event("model-created", modelInfo) { notif ->
//                notif.subscribed(
//                    defaultEmail = Frequency.immediately(),
//                    defaultSms = Frequency.immediately(),
//                    defaultPush = null,
//                    defaultInApp = Frequency.immediately()
//                ) { event ->
//                    // Only owner receives notification
//                    setOf(event.subject.ownerId)
//                }
//                notif.content { event ->
//                    { user -> TestNotificationContent.MODEL_CREATED }
//                }
//            }
//
//            val modelDeleted = event("model-deleted", modelInfo) { notif ->
//                notif.subscribed(
//                    defaultEmail = Frequency.daily(9, 0),
//                    defaultSms = Frequency.daily(9, 0),
//                    defaultPush = Frequency.immediately(),
//                    defaultInApp = Frequency.immediately()
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
//    // ===== Default Frequency Tests =====
//
//    @Test
//    fun `default frequencies used when no user override exists`() {
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "DefaultUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                val eventDef = TestServer.Notifications.modelCreated.event
//                val model = TestModel(ownerId = user._id)
//                val event = Event(eventDef, model)
//
//                val subscriptions = TestServer.Notifications.subscriptions.subscribed(event)
//
//                val sub = subscriptions.find { it.user == user._id }!!
//
//                // Should have default frequencies
//                assertEquals(Frequency.immediately(), sub.email)
//                assertEquals(Frequency.immediately(), sub.sms)
//                assertNull(sub.push)
//                assertEquals(Frequency.immediately(), sub.inApp)
//            }
//        }
//    }
//
//    // ===== User Override Tests =====
//
//    @Test
//    fun `user override replaces default frequencies`() {
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "OverrideUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                val eventDef = TestServer.Notifications.modelCreated.event
//
//                // Insert user override
//                val userPreference = NotificationSendMethods(
//                    _id = UserEventType(user._id, eventDef.untyped),
//                    email = Frequency.daily(9, 0),
//                    sms = null,  // Disable SMS
//                    push = Frequency.immediately(),
//                    inApp = Frequency.immediately()
//                )
//                TestServer.Notifications.subscriptions.info.table().insertOne(userPreference)
//
//                val model = TestModel(ownerId = user._id)
//                val event = Event(eventDef, model)
//
//                val subscriptions = TestServer.Notifications.subscriptions.subscribed(event)
//
//                val sub = subscriptions.find { it.user == user._id }!!
//
//                // Should have user's custom frequencies
//                assertEquals(Frequency.daily(9, 0), sub.email)
//                assertNull(sub.sms)  // User disabled SMS
//                assertEquals(Frequency.immediately(), sub.push)
//            }
//        }
//    }
//
//    @Test
//    fun `user can disable specific channels`() {
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "DisableUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                val eventDef = TestServer.Notifications.modelCreated.event
//
//                // User disables all channels except inApp
//                val userPreference = NotificationSendMethods(
//                    _id = UserEventType(user._id, eventDef.untyped),
//                    email = null,
//                    sms = null,
//                    push = null,
//                    inApp = Frequency.immediately()
//                )
//                TestServer.Notifications.subscriptions.info.table().insertOne(userPreference)
//
//                val model = TestModel(ownerId = user._id)
//                val event = Event(eventDef, model)
//
//                val subscriptions = TestServer.Notifications.subscriptions.subscribed(event)
//
//                val sub = subscriptions.find { it.user == user._id }!!
//
//                assertNull(sub.email)
//                assertNull(sub.sms)
//                assertNull(sub.push)
//                assertEquals(Frequency.immediately(), sub.inApp)
//            }
//        }
//    }
//
//    @Test
//    fun `modelDeleted event uses daily defaults`() {
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "DailyUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                val eventDef = TestServer.Notifications.modelDeleted.event
//                val model = TestModel(ownerId = user._id)
//                val event = Event(eventDef, model)
//
//                val subscriptions = TestServer.Notifications.subscriptions.subscribed(event)
//
//                val sub = subscriptions.find { it.user == user._id }!!
//
//                // modelDeleted configured with daily defaults
//                assertEquals(Frequency.daily(9, 0), sub.email)
//                assertEquals(Frequency.daily(9, 0), sub.sms)
//                assertEquals(Frequency.immediately(), sub.push)
//            }
//        }
//    }
//
//    @Test
//    fun `user override on different event type`() {
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "MultiEventUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                // Override only for modelDeleted, not modelCreated
//                val userPref = NotificationSendMethods(
//                    _id = UserEventType(user._id, TestServer.Notifications.modelDeleted.event.untyped),
//                    email = Frequency.weekly(kotlinx.datetime.DayOfWeek.FRIDAY, 10, 0),
//                    sms = null,
//                    push = Frequency.immediately(),
//                    inApp = Frequency.immediately()
//                )
//                TestServer.Notifications.subscriptions.info.table().insertOne(userPref)
//
//                // Test modelCreated uses defaults
//                val createdEvent = Event(
//                    TestServer.Notifications.modelCreated.event,
//                    TestModel(ownerId = user._id)
//                )
//                val createdSubs = TestServer.Notifications.subscriptions.subscribed(createdEvent)
//                val createdSub = createdSubs.find { it.user == user._id }!!
//                assertEquals(Frequency.immediately(), createdSub.email) // default
//
//                // Test modelDeleted uses override
//                val deletedEvent = Event(
//                    TestServer.Notifications.modelDeleted.event,
//                    TestModel(ownerId = user._id)
//                )
//                val deletedSubs = TestServer.Notifications.subscriptions.subscribed(deletedEvent)
//                val deletedSub = deletedSubs.find { it.user == user._id }!!
//                assertEquals(
//                    Frequency.weekly(kotlinx.datetime.DayOfWeek.FRIDAY, 10, 0),
//                    deletedSub.email
//                )
//            }
//        }
//    }
//}
