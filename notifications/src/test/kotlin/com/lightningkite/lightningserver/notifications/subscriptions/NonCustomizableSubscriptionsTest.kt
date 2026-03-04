//package com.lightningkite.lightningserver.notifications.subscriptions
//
//import com.lightningkite.lightningserver.auth.require
//import com.lightningkite.lightningserver.definition.builder.ServerBuilder
//import com.lightningkite.lightningserver.notifications.*
//import com.lightningkite.lightningserver.notifications.events.Event
//import com.lightningkite.lightningserver.notifications.events.EventDefinition
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
//import kotlin.test.assertNotNull
//import kotlin.test.assertNull
//import kotlin.test.assertTrue
//import kotlin.uuid.Uuid
//
///**
// * Tests for [NonCustomizableSubscriptions].
// * Verifies programmatic-only subscriptions work correctly.
// *
// * These tests use owner-based events to avoid test isolation issues
// * that arise when using `users.table().all()` across multiple tests.
// */
//class NonCustomizableSubscriptionsTest {
//
//    // ===== Test Server Definition =====
//
//    private object TestServer : ServerBuilder() {
//        val database = setting("database", Database.Settings())
//        val cache = setting("cache", Cache.Settings())
//        val sms = setting("sms", SMS.Settings())
//        val email = setting("email", EmailService.Settings())
//
//        val userInfo: ModelInfo<TestUser, TestUser, Uuid> = database.testModelInfo()
//
//        val modelInfo: ModelInfo<TestUser, TestModel, Uuid> = database.testModelInfo()
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
//            // Event that notifies model owner - easier for isolated testing
//            val modelCreated = event("model-created", modelInfo) { notif ->
//                notif.subscribed(
//                    email = Frequency.immediately(),
//                    sms = Frequency.immediately(),
//                    push = null,
//                    inApp = Frequency.immediately()
//                ) { event ->
//                    // Only owner receives this notification
//                    setOf(event.subject.ownerId)
//                }
//                notif.content { event ->
//                    { user -> TestNotificationContent.MODEL_CREATED }
//                }
//            }
//
//            val modelDeleted = event("model-deleted", modelInfo) { notif ->
//                notif.subscribed(
//                    email = Frequency.daily(9, 0),
//                    sms = null,
//                    push = Frequency.immediately(),
//                    inApp = Frequency.immediately()
//                ) { event ->
//                    // Only the owner gets notified
//                    setOf(event.subject.ownerId)
//                }
//                notif.content { event ->
//                    { user -> TestNotificationContent.MODEL_DELETED }
//                }
//            }
//        }
//    }
//
//    // ===== Basic Subscription Tests =====
//
//    @Test
//    fun `subscribed returns correct subscriber for owner event`() {
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "TestUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                val eventDef = TestServer.Notifications.modelCreated.event
//                val model = TestModel(ownerId = user._id)
//                val event = Event(eventDef, model)
//
//                val subscriptions = TestServer.Notifications.subscriptions.subscribed(event)
//
//                // Should have exactly the owner
//                assertEquals(1, subscriptions.size)
//                assertEquals(user._id, subscriptions.first().user)
//            }
//        }
//    }
//
//    @Test
//    fun `event subscribers has correct frequencies`() {
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "FrequencyUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                val eventDef = TestServer.Notifications.modelCreated.event
//                val model = TestModel(ownerId = user._id)
//                val event = Event(eventDef, model)
//
//                val subscriptions = TestServer.Notifications.subscriptions.subscribed(event)
//                val sub = subscriptions.find { it.user == user._id }!!
//
//                // Verify frequencies match what was configured
//                assertEquals(Frequency.immediately(), sub.email)
//                assertEquals(Frequency.immediately(), sub.sms)
//                assertNull(sub.push)  // Was set to null
//                assertEquals(Frequency.immediately(), sub.inApp)
//            }
//        }
//    }
//
//    @Test
//    fun `modelDeleted only notifies owner`() {
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val owner = TestUser(name = "Owner")
//                val other = TestUser(name = "Other")
//                TestServer.userInfo.table().insertOne(owner)
//                TestServer.userInfo.table().insertOne(other)
//
//                val eventDef = TestServer.Notifications.modelDeleted.event
//                val model = TestModel(ownerId = owner._id)
//                val event = Event(eventDef, model)
//
//                val subscriptions = TestServer.Notifications.subscriptions.subscribed(event)
//
//                // Only owner should be subscribed
//                assertTrue(subscriptions.any { it.user == owner._id })
//                assertTrue(subscriptions.none { it.user == other._id })
//            }
//        }
//    }
//
//    @Test
//    fun `null frequency disables channel`() {
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val owner = TestUser(name = "Owner")
//                TestServer.userInfo.table().insertOne(owner)
//
//                // modelDeleted has sms = null
//                val eventDef = TestServer.Notifications.modelDeleted.event
//                val model = TestModel(ownerId = owner._id)
//                val event = Event(eventDef, model)
//
//                val subs = TestServer.Notifications.subscriptions.subscribed(event)
//                val sub = subs.find { it.user == owner._id }!!
//
//                // SMS should be null (disabled)
//                assertNull(sub.sms)
//                // Email should be daily
//                assertNotNull(sub.email)
//            }
//        }
//    }
//
//    @Test
//    fun `modelDeleted has daily frequency for email`() {
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val owner = TestUser(name = "Owner")
//                TestServer.userInfo.table().insertOne(owner)
//
//                val eventDef = TestServer.Notifications.modelDeleted.event
//                val model = TestModel(ownerId = owner._id)
//                val event = Event(eventDef, model)
//
//                val subs = TestServer.Notifications.subscriptions.subscribed(event)
//                val sub = subs.find { it.user == owner._id }!!
//
//                // modelDeleted configured with daily(9, 0) for email
//                assertEquals(Frequency.daily(9, 0), sub.email)
//                // Push should be immediate
//                assertEquals(Frequency.immediately(), sub.push)
//            }
//        }
//    }
//}
//
///**
// * Tests for multiple listeners on same event with NonCustomizableSubscriptions.
// */
//class NonCustomizableSubscriptionsMergingTest {
//
//    private object MergingTestServer : ServerBuilder() {
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
//            val modelEvent: EventDefinition<TestUser, TestModel, Uuid>
//
//            init {
//                modelEvent = EventDefinition("model-merged", emptySet(), modelInfo, registry)
//
//                // First listener: delayed email
//                subscriptions.setSubscribed(
//                    modelEvent,
//                    email = Frequency.delayed(kotlin.time.Duration.parse("PT1H")),
//                    sms = null,
//                    push = null,
//                    inApp = Frequency.immediately()
//                ) { event ->
//                    setOf(event.subject.ownerId)
//                }
//
//                // Second listener: immediate email
//                subscriptions.setSubscribed(
//                    modelEvent,
//                    email = Frequency.immediately(),
//                    sms = Frequency.immediately(),
//                    push = null,
//                    inApp = null
//                ) { event ->
//                    setOf(event.subject.ownerId)
//                }
//
//                content.setContent(modelEvent) { event ->
//                    { user -> TestNotificationContent.MODEL_CREATED }
//                }
//            }
//        }
//    }
//
//    @Test
//    fun `multiple listeners merge to earliest frequency`() {
//        MergingTestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "MergeUser")
//                MergingTestServer.userInfo.table().insertOne(user)
//
//                val eventDef = MergingTestServer.Notifications.modelEvent
//                val model = TestModel(ownerId = user._id)
//                val event = Event(eventDef, model)
//
//                val subs = MergingTestServer.Notifications.subscriptions.subscribed(event)
//
//                val sub = subs.find { it.user == user._id }!!
//
//                // Email should merge to immediately (earliest)
//                assertEquals(Frequency.immediately(), sub.email)
//                // SMS should be immediately (from second listener)
//                assertEquals(Frequency.immediately(), sub.sms)
//                // inApp should be immediately (from first listener)
//                assertEquals(Frequency.immediately(), sub.inApp)
//            }
//        }
//    }
//}
