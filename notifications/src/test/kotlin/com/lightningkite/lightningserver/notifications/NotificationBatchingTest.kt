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
//import com.lightningkite.services.email.Email
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
//import kotlin.test.assertNull
//import kotlin.test.assertTrue
//import kotlin.time.Duration.Companion.hours
//import kotlin.uuid.Uuid
//
///**
// * Tests for notification batching behavior.
// * Verifies that notifications are properly grouped by sendAt time
// * and channel frequencies work correctly.
// */
//class NotificationBatchingTest {
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
//            // Immediate event - all channels immediate
//            val allImmediate = event("all-immediate", modelInfo) { notif ->
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
//            // Email delayed, others immediate
//            val emailDelayed = event("email-delayed", modelInfo) { notif ->
//                notif.subscribed(
//                    email = Frequency.delayed(1.hours),
//                    sms = Frequency.immediately(),
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
//            // All channels delayed
//            val allDelayed = event("all-delayed", modelInfo) { notif ->
//                notif.subscribed(
//                    email = Frequency.delayed(1.hours),
//                    sms = Frequency.delayed(1.hours),
//                    push = null,
//                    inApp = Frequency.delayed(1.hours)
//                ) { event ->
//                    setOf(event.subject.ownerId)
//                }
//                notif.content { event ->
//                    { user -> TestNotificationContent.MODEL_DELETED }
//                }
//            }
//
//            // Email only - others disabled
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
//                    { user -> TestNotificationContent.MODEL_CREATED }
//                }
//            }
//
//            // SMS only - others disabled
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
//                    { user -> TestNotificationContent.MODEL_UPDATED }
//                }
//            }
//
//            // InApp only
//            val inAppOnly = event("inapp-only", modelInfo) { notif ->
//                notif.subscribed(
//                    email = null,
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
//    // ===== Frequency Configuration Tests =====
//
//    @Test
//    fun `null frequency disables email channel`() {
//        var testEmail: TestEmailService? = null
//
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context).also { testEmail = it }
//        }) {
//            runBlocking {
//                val user = TestUser(name = "NoEmailUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                // Trigger SMS-only event
//                val model = TestModel(name = "Test", ownerId = user._id)
//                TestServer.Notifications.smsOnly(model)
//
//                // No email should be sent
//                assertTrue(testEmail!!.sentEmails.isEmpty())
//
//                // Notification should have null email
//                val notifications = TestServer.Notifications.dispatcher.info.table().all().toList()
//                val userNotif = notifications.find { it.user == user._id }
//                assertNotNull(userNotif)
//                assertNull(userNotif.email)
//            }
//        }
//    }
//
//    @Test
//    fun `null frequency disables SMS channel`() {
//        var testSms: TestSMS? = null
//
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context).also { testSms = it }
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "NoSmsUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                // Trigger email-only event
//                val model = TestModel(name = "Test", ownerId = user._id)
//                TestServer.Notifications.emailOnly(model)
//
//                // No SMS should be sent
//                assertTrue(testSms!!.messageHistory.isEmpty())
//
//                // Notification should have null SMS
//                val notifications = TestServer.Notifications.dispatcher.info.table().all().toList()
//                val userNotif = notifications.find { it.user == user._id }
//                assertNotNull(userNotif)
//                assertNull(userNotif.sms)
//            }
//        }
//    }
//
//    @Test
//    fun `null frequency disables inApp channel`() {
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "NoInAppUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                // Trigger email-only event (inApp is null)
//                val model = TestModel(name = "Test", ownerId = user._id)
//                TestServer.Notifications.emailOnly(model)
//
//                // Notification should have null inApp
//                val notifications = TestServer.Notifications.dispatcher.info.table().all().toList()
//                val userNotif = notifications.find { it.user == user._id }
//                assertNotNull(userNotif)
//                assertNull(userNotif.inApp)
//            }
//        }
//    }
//
//    // ===== Channel-Specific Frequency Tests =====
//
//    @Test
//    fun `different channels can have different frequencies`() {
//        var testEmail: TestEmailService? = null
//        var testSms: TestSMS? = null
//
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context).also { testSms = it }
//            email setStatic TestEmailService("email", context).also { testEmail = it }
//        }) {
//            runBlocking {
//                val user = TestUser(name = "MixedFreqUser", email = "test@example.com", phone = "1234567890")
//                TestServer.userInfo.table().insertOne(user)
//
//                // Trigger event with email delayed, SMS immediate
//                val model = TestModel(name = "Test", ownerId = user._id)
//                TestServer.Notifications.emailDelayed(model)
//
//                // SMS should be sent immediately
//                assertTrue(testSms!!.messageHistory.isNotEmpty())
//
//                // Email should NOT be sent (delayed)
//                assertTrue(testEmail!!.sentEmails.isEmpty())
//
//                // Check notification state
//                val notifications = TestServer.Notifications.dispatcher.info.table().all().toList()
//                val userNotif = notifications.find { it.user == user._id }
//                assertNotNull(userNotif)
//
//                // SMS should be marked sent
//                assertNotNull(userNotif.sms)
//                assertTrue(userNotif.sms!!.sent)
//
//                // Email should exist but not be sent
//                assertNotNull(userNotif.email)
//                assertFalse(userNotif.email!!.sent)
//            }
//        }
//    }
//
//    @Test
//    fun `all immediate frequencies send all channels at once`() {
//        var testEmail: TestEmailService? = null
//        var testSms: TestSMS? = null
//
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context).also { testSms = it }
//            email setStatic TestEmailService("email", context).also { testEmail = it }
//        }) {
//            runBlocking {
//                val user = TestUser(name = "AllImmediateUser", email = "test@example.com", phone = "1234567890")
//                TestServer.userInfo.table().insertOne(user)
//
//                // Trigger all-immediate event
//                val model = TestModel(name = "Test", ownerId = user._id)
//                TestServer.Notifications.allImmediate(model)
//
//                // Both email and SMS should be sent
//                assertTrue(testEmail!!.sentEmails.isNotEmpty())
//                assertTrue(testSms!!.messageHistory.isNotEmpty())
//
//                // All channels should be marked sent
//                val notifications = TestServer.Notifications.dispatcher.info.table().all().toList()
//                val userNotif = notifications.find { it.user == user._id }
//                assertNotNull(userNotif)
//                assertTrue(userNotif.email?.sent ?: false)
//                assertTrue(userNotif.sms?.sent ?: false)
//                assertTrue(userNotif.inApp?.sent ?: false)
//            }
//        }
//    }
//
//    @Test
//    fun `all delayed frequencies send nothing immediately`() {
//        var testEmail: TestEmailService? = null
//        var testSms: TestSMS? = null
//
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context).also { testSms = it }
//            email setStatic TestEmailService("email", context).also { testEmail = it }
//        }) {
//            runBlocking {
//                val user = TestUser(name = "AllDelayedUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                // Trigger all-delayed event
//                val model = TestModel(name = "Test", ownerId = user._id)
//                TestServer.Notifications.allDelayed(model)
//
//                // Nothing should be sent immediately
//                assertTrue(testEmail!!.sentEmails.isEmpty())
//                assertTrue(testSms!!.messageHistory.isEmpty())
//
//                // All channels should exist but not be sent
//                val notifications = TestServer.Notifications.dispatcher.info.table().all().toList()
//                val userNotif = notifications.find { it.user == user._id }
//                assertNotNull(userNotif)
//                assertNotNull(userNotif.email)
//                assertFalse(userNotif.email!!.sent)
//                assertNotNull(userNotif.sms)
//                assertFalse(userNotif.sms!!.sent)
//                assertNotNull(userNotif.inApp)
//                assertFalse(userNotif.inApp!!.sent)
//            }
//        }
//    }
//
//    // ===== Multiple Notifications Batching Tests =====
//
//    @Test
//    fun `multiple immediate notifications are batched together for same user`() {
//        var testEmail: TestEmailService? = null
//
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context).also { testEmail = it }
//        }) {
//            runBlocking {
//                val user = TestUser(name = "BatchUser", email = "batch@example.com")
//                TestServer.userInfo.table().insertOne(user)
//
//                // Trigger multiple immediate events for same user
//                TestServer.Notifications.allImmediate(TestModel(name = "Model1", ownerId = user._id))
//                TestServer.Notifications.allImmediate(TestModel(name = "Model2", ownerId = user._id))
//                TestServer.Notifications.allImmediate(TestModel(name = "Model3", ownerId = user._id))
//
//                // Multiple notifications should be created
//                val notifications = TestServer.Notifications.dispatcher.info.table().all().toList()
//                val userNotifs = notifications.filter { it.user == user._id }
//                assertTrue(userNotifs.size >= 3)
//
//                // Emails should have been sent (batched)
//                assertTrue(testEmail!!.sentEmails.isNotEmpty())
//            }
//        }
//    }
//
//    @Test
//    fun `inApp only creates notification without sending email or SMS`() {
//        var testEmail: TestEmailService? = null
//        var testSms: TestSMS? = null
//
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context).also { testSms = it }
//            email setStatic TestEmailService("email", context).also { testEmail = it }
//        }) {
//            runBlocking {
//                val user = TestUser(name = "InAppOnlyUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                // Trigger inApp-only event
//                val model = TestModel(name = "Test", ownerId = user._id)
//                TestServer.Notifications.inAppOnly(model)
//
//                // No email or SMS should be sent
//                assertTrue(testEmail!!.sentEmails.isEmpty())
//                assertTrue(testSms!!.messageHistory.isEmpty())
//
//                // Notification should exist with inApp marked as sent
//                val notifications = TestServer.Notifications.dispatcher.info.table().all().toList()
//                val userNotif = notifications.find { it.user == user._id }
//                assertNotNull(userNotif)
//                assertNotNull(userNotif.inApp)
//                assertTrue(userNotif.inApp!!.sent)
//                assertNull(userNotif.email)
//                assertNull(userNotif.sms)
//            }
//        }
//    }
//
//    // ===== SendAt Time Tests =====
//
//    @Test
//    fun `delayed notification has future sendAt time`() {
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "SendAtUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                // Trigger delayed event
//                val model = TestModel(name = "Test", ownerId = user._id)
//                TestServer.Notifications.emailDelayed(model)
//
//                // Check notification sendAt times
//                val notifications = TestServer.Notifications.dispatcher.info.table().all().toList()
//                val userNotif = notifications.find { it.user == user._id }
//                assertNotNull(userNotif)
//
//                // Email sendAt should be in the future
//                assertNotNull(userNotif.email)
//                // SMS sendAt should be in the past (was sent immediately)
//                assertNotNull(userNotif.sms)
//                assertTrue(userNotif.sms!!.sent)
//            }
//        }
//    }
//
//    @Test
//    fun `immediate notification has current or past sendAt time`() {
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "ImmediateSendAtUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                // Trigger immediate event
//                val model = TestModel(name = "Test", ownerId = user._id)
//                TestServer.Notifications.allImmediate(model)
//
//                // Check notification - all should be sent
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
//    // ===== User Contact Info Tests =====
//
//    @Test
//    fun `notifications are created for all configured channels`() {
//        var testSms: TestSMS? = null
//        var testEmail: TestEmailService? = null
//
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context).also { testSms = it }
//            email setStatic TestEmailService("email", context).also { testEmail = it }
//        }) {
//            runBlocking {
//                val user = TestUser(name = "MultiChannelUser", phone = "9876543210", email = "multi@example.com")
//                TestServer.userInfo.table().insertOne(user)
//
//                // Trigger event with both email and SMS
//                val model = TestModel(name = "Test", ownerId = user._id)
//                TestServer.Notifications.allImmediate(model)
//
//                // Both SMS and email should be sent
//                assertTrue(testSms!!.messageHistory.isNotEmpty())
//                assertTrue(testEmail!!.sentEmails.isNotEmpty())
//            }
//        }
//    }
//
//    @Test
//    fun `multiple events create separate notifications`() {
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "MultiEventUser", email = "multi@example.com")
//                TestServer.userInfo.table().insertOne(user)
//
//                // Trigger different event types
//                val model1 = TestModel(name = "Model1", ownerId = user._id)
//                val model2 = TestModel(name = "Model2", ownerId = user._id)
//                TestServer.Notifications.allImmediate(model1)
//                TestServer.Notifications.emailOnly(model2)
//
//                // Multiple notifications should be created for this user
//                val notifications = TestServer.Notifications.dispatcher.info.table().all().toList()
//                val userNotifs = notifications.filter { it.user == user._id }
//                assertTrue(userNotifs.size >= 2)
//            }
//        }
//    }
//}
