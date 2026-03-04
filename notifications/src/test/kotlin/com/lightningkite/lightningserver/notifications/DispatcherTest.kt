//package com.lightningkite.lightningserver.notifications
//
//import com.lightningkite.lightningserver.auth.require
//import com.lightningkite.lightningserver.definition.builder.ServerBuilder
//import com.lightningkite.lightningserver.notifications.events.event
//import com.lightningkite.lightningserver.notifications.subscriptions.NonCustomizableSubscriptions
//import com.lightningkite.lightningserver.notifications.subscriptions.subscribed
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
//import kotlin.test.assertFalse
//import kotlin.test.assertNotNull
//import kotlin.test.assertTrue
//import kotlin.time.Duration.Companion.hours
//import kotlin.uuid.Uuid
//
///**
// * Tests for [NotificationBulkDispatcher].
// * Verifies dispatch, sending, batching, and channel handling.
// *
// * These tests use the event-driven approach where events trigger notification
// * creation, rather than manually constructing Notification objects.
// */
//class DispatcherTest {
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
//            // Immediately sent event
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
//
//            // Delayed event (1 hour)
//            val modelDelayed = event("model-delayed", modelInfo) { notif ->
//                notif.subscribed(
//                    email = Frequency.delayed(1.hours),
//                    sms = null,
//                    push = null,
//                    inApp = Frequency.immediately()
//                ) { event ->
//                    setOf(event.subject.ownerId)
//                }
//                notif.content { event ->
//                    { user -> TestNotificationContent.MODEL_UPDATED }
//                }
//            }
//
//            // Email only event
//            val emailOnly = event("email-only", modelInfo) { notif ->
//                notif.subscribed(
//                    email = Frequency.immediately(),
//                    sms = null,
//                    push = null,
//                    inApp = null
//                ) { event ->
//                    setOf(event.subject.ownerId)
//                }
//                notif.content { event ->
//                    { user -> TestNotificationContent.MODEL_UPDATED }
//                }
//            }
//
//            // SMS only event
//            val smsOnly = event("sms-only", modelInfo) { notif ->
//                notif.subscribed(
//                    email = null,
//                    sms = Frequency.immediately(),
//                    push = null,
//                    inApp = null
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
//    // ===== Event-Triggered Dispatch Tests =====
//
//    @Test
//    fun `event creates notification in database`() {
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "DispatchUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                // Trigger event
//                val model = TestModel(name = "Test", ownerId = user._id)
//                TestServer.Notifications.modelCreated(model)
//
//                // Verify notification was created
//                val notifications = TestServer.Notifications.dispatcher.info.table().all().toList()
//                val userNotif = notifications.find { it.user == user._id }
//
//                assertNotNull(userNotif)
//                assertEquals(TestNotificationContent.MODEL_CREATED, userNotif.content)
//            }
//        }
//    }
//
//    @Test
//    fun `immediate event sends email right away`() {
//        var testEmail: TestEmailService? = null
//
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context).also { testEmail = it }
//        }) {
//            runBlocking {
//                val user = TestUser(name = "ImmediateUser", email = "test@example.com")
//                TestServer.userInfo.table().insertOne(user)
//
//                // Trigger immediate event
//                val model = TestModel(name = "Test", ownerId = user._id)
//                TestServer.Notifications.modelCreated(model)
//
//                // Email should have been sent
//                assertTrue(testEmail!!.sentEmails.isNotEmpty())
//                assertEquals(TestNotificationContent.MODEL_CREATED.title, testEmail!!.sentEmails.first().subject)
//            }
//        }
//    }
//
//    @Test
//    fun `immediate event sends SMS right away`() {
//        var testSms: TestSMS? = null
//
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context).also { testSms = it }
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "SmsUser", phone = "1234567890")
//                TestServer.userInfo.table().insertOne(user)
//
//                // Trigger immediate event
//                val model = TestModel(name = "Test", ownerId = user._id)
//                TestServer.Notifications.modelCreated(model)
//
//                // SMS should have been sent
//                assertTrue(testSms!!.messageHistory.isNotEmpty())
//            }
//        }
//    }
//
//    @Test
//    fun `delayed event does not send immediately`() {
//        var testEmail: TestEmailService? = null
//
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context).also { testEmail = it }
//        }) {
//            runBlocking {
//                val user = TestUser(name = "DelayedUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                // Trigger delayed event (1 hour delay)
//                val model = TestModel(name = "Delayed", ownerId = user._id)
//                TestServer.Notifications.modelDelayed(model)
//
//                // Notification should be created
//                val notifications = TestServer.Notifications.dispatcher.info.table().all().toList()
//                val userNotif = notifications.find { it.user == user._id }
//                assertNotNull(userNotif)
//
//                // Email should NOT have been sent (scheduled for 1 hour later)
//                assertTrue(testEmail!!.sentEmails.isEmpty())
//
//                // But email SendInfo should exist and not be sent yet
//                assertNotNull(userNotif.email)
//                assertFalse(userNotif.email!!.sent)
//            }
//        }
//    }
//
//    @Test
//    fun `inApp is always sent immediately even when email is delayed`() {
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "InAppUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                // Trigger delayed event (email delayed, but inApp is immediate)
//                val model = TestModel(name = "Test", ownerId = user._id)
//                TestServer.Notifications.modelDelayed(model)
//
//                // Check notification
//                val notifications = TestServer.Notifications.dispatcher.info.table().all().toList()
//                val userNotif = notifications.find { it.user == user._id }
//                assertNotNull(userNotif)
//
//                // InApp should be marked as sent (it's immediate)
//                assertNotNull(userNotif.inApp)
//                assertTrue(userNotif.inApp!!.sent)
//
//                // Email should NOT be sent yet (delayed)
//                assertNotNull(userNotif.email)
//                assertFalse(userNotif.email!!.sent)
//            }
//        }
//    }
//
//    // ===== Channel Configuration Tests =====
//
//    @Test
//    fun `null channel disables that notification type`() {
//        var testSms: TestSMS? = null
//
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context).also { testSms = it }
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "EmailOnlyUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                // Trigger email-only event (SMS is null)
//                val model = TestModel(name = "Test", ownerId = user._id)
//                TestServer.Notifications.emailOnly(model)
//
//                // No SMS should be sent
//                assertTrue(testSms!!.messageHistory.isEmpty())
//
//                // Notification should have no SMS info
//                val notifications = TestServer.Notifications.dispatcher.info.table().all().toList()
//                val userNotif = notifications.find { it.user == user._id }
//                assertNotNull(userNotif)
//                kotlin.test.assertNull(userNotif.sms)
//            }
//        }
//    }
//
//    @Test
//    fun `SMS only event does not send email`() {
//        var testEmail: TestEmailService? = null
//
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context).also { testEmail = it }
//        }) {
//            runBlocking {
//                val user = TestUser(name = "SmsOnlyUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                // Trigger SMS-only event
//                val model = TestModel(name = "Test", ownerId = user._id)
//                TestServer.Notifications.smsOnly(model)
//
//                // No email should be sent
//                assertTrue(testEmail!!.sentEmails.isEmpty())
//            }
//        }
//    }
//
//    // ===== Sent Marking Tests =====
//
//    @Test
//    fun `channels are marked as sent after delivery`() {
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "MarkSentUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                // Trigger immediate event
//                val model = TestModel(name = "Test", ownerId = user._id)
//                TestServer.Notifications.modelCreated(model)
//
//                // Check notification
//                val notifications = TestServer.Notifications.dispatcher.info.table().all().toList()
//                val userNotif = notifications.find { it.user == user._id }
//                assertNotNull(userNotif)
//
//                // All immediate channels should be marked as sent
//                assertTrue(userNotif.email?.sent ?: false)
//                assertTrue(userNotif.sms?.sent ?: false)
//                assertTrue(userNotif.inApp?.sent ?: false)
//            }
//        }
//    }
//
//    // ===== Multiple Users Tests =====
//
//    @Test
//    fun `notifications for different users are processed separately`() {
//        var testEmail: TestEmailService? = null
//
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context).also { testEmail = it }
//        }) {
//            runBlocking {
//                val user1 = TestUser(name = "User1", email = "user1@example.com")
//                val user2 = TestUser(name = "User2", email = "user2@example.com")
//                TestServer.userInfo.table().insertOne(user1)
//                TestServer.userInfo.table().insertOne(user2)
//
//                // Create model owned by both users (impossible in reality, but for testing)
//                // Instead, create two separate models
//                val model1 = TestModel(name = "Model1", ownerId = user1._id)
//                val model2 = TestModel(name = "Model2", ownerId = user2._id)
//
//                TestServer.Notifications.modelCreated(model1)
//                TestServer.Notifications.modelCreated(model2)
//
//                // Two separate emails should be sent (one per user) in this test
//                // Note: sentEmails accumulates across tests, so check it has at least 2
//                assertTrue(testEmail!!.sentEmails.size >= 2)
//
//                // Both users should have notifications
//                val notifications = TestServer.Notifications.dispatcher.info.table().all().toList()
//                assertTrue(notifications.any { it.user == user1._id })
//                assertTrue(notifications.any { it.user == user2._id })
//            }
//        }
//    }
//
//    // ===== Content Verification Tests =====
//
//    @Test
//    fun `email content matches notification content`() {
//        var testEmail: TestEmailService? = null
//
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context).also { testEmail = it }
//        }) {
//            runBlocking {
//                val user = TestUser(name = "ContentUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                // Trigger event
//                val model = TestModel(name = "Test", ownerId = user._id)
//                TestServer.Notifications.modelCreated(model)
//
//                // Verify email content
//                val email = testEmail!!.sentEmails.first()
//                assertEquals(TestNotificationContent.MODEL_CREATED.title, email.subject)
//            }
//        }
//    }
//
//    @Test
//    fun `SMS content matches notification content`() {
//        var testSms: TestSMS? = null
//
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context).also { testSms = it }
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "SmsContentUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                // Trigger event
//                val model = TestModel(name = "Test", ownerId = user._id)
//                TestServer.Notifications.modelCreated(model)
//
//                // Verify SMS was sent (content verification happens in TestDispatcherBase)
//                assertTrue(testSms!!.messageHistory.isNotEmpty())
//            }
//        }
//    }
//}
