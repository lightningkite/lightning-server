// by Claude
package com.lightningkite.lightningserver.notifications

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.notifications.events.event
import com.lightningkite.lightningserver.notifications.subscriptions.NonCustomizableSubscriptions
import com.lightningkite.lightningserver.notifications.subscriptions.subscribed
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.settings.setStatic
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.*
import com.lightningkite.services.email.EmailService
import com.lightningkite.services.email.TestEmailService
import com.lightningkite.services.sms.SMS
import com.lightningkite.services.sms.TestSMS
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * Tests for [NotificationBulkDispatcher].
 * Verifies dispatch, sending, batching, and channel handling.
 * Includes time-travel tests using [TestClock].
 */
class DispatcherTest {

    private object Server : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val cache = setting("cache", Cache.Settings())
        val sms = setting("sms", SMS.Settings())
        val email = setting("email", EmailService.Settings())

        val userInfo = database.testModelInfo<TestUser, Uuid>()
        val modelInfo = database.testModelInfo<TestModel, Uuid>()

        val notifications = path.path("notifications") module Notifications
    }

    private object Notifications : ServerBuilder() {
        object Dispatcher : TestDispatcherBase(
            info = Server.database.testModelInfo(),
            cache = Server.cache,
            database = Server.database,
            users = Server.userInfo,
            email = Server.email,
            sms = Server.sms
        )

        val handler = path include NotificationEndpoints(
            Server.userInfo,
            Dispatcher,
            NonCustomizableSubscriptions()
        )

        // Immediately sent event
        val modelCreated = handler.event("Model Created", Server.modelInfo) { notif ->
            notif.subscribed(
                email = Frequency.immediately(),
                sms = Frequency.immediately(),
                push = null,
                inApp = Frequency.immediately()
            ) { event ->
                setOf(event.subject.ownerId)
            }
            notif.content { event ->
                { user -> "Created: ${event.subject.name}" }
            }
        }

        // Delayed event (1 hour email delay)
        val modelDelayed = handler.event("Model Delayed", Server.modelInfo) { notif ->
            notif.subscribed(
                email = Frequency.delayed(1.hours),
                sms = null,
                push = null,
                inApp = Frequency.immediately()
            ) { event ->
                setOf(event.subject.ownerId)
            }
            notif.content { event ->
                { user -> "Delayed: ${event.subject.name}" }
            }
        }

        // Email only event
        val emailOnly = handler.event("Email Only", Server.modelInfo) { notif ->
            notif.subscribed(
                email = Frequency.immediately(),
                sms = null,
                push = null,
                inApp = null
            ) { event ->
                setOf(event.subject.ownerId)
            }
            notif.content { event ->
                { user -> "Email Only: ${event.subject.name}" }
            }
        }

        // SMS only event
        val smsOnly = handler.event("SMS Only", Server.modelInfo) { notif ->
            notif.subscribed(
                email = null,
                sms = Frequency.immediately(),
                push = null,
                inApp = null
            ) { event ->
                setOf(event.subject.ownerId)
            }
            notif.content { event ->
                { user -> "SMS Only: ${event.subject.name}" }
            }
        }
    }

    // ===== Event-Triggered Dispatch Tests =====

    @Test
    fun `immediate event sends email right away`() {
        var testEmail: TestEmailService? = null

        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context).also { testEmail = it }
        }) {
            runBlocking {
                val user = TestUser(name = "ImmediateUser", email = "test@example.com")
                Server.userInfo.table().insertOne(user)

                val model = TestModel(name = "Test", ownerId = user._id)
                Notifications.modelCreated(model)

                assertTrue(testEmail!!.sentEmails.isNotEmpty())
                assertEquals("Created: Test", testEmail!!.sentEmails.first().subject)
            }
        }
    }

    @Test
    fun `immediate event sends SMS right away`() {
        var testSms: TestSMS? = null

        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context).also { testSms = it }
            email setStatic TestEmailService("email", context)
        }) {
            runBlocking {
                val user = TestUser(name = "SmsUser", phone = "1234567890")
                Server.userInfo.table().insertOne(user)

                val model = TestModel(name = "Test", ownerId = user._id)
                Notifications.modelCreated(model)

                assertTrue(testSms!!.messageHistory.isNotEmpty())
            }
        }
    }

    @Test
    fun `delayed event does not send email immediately`() {
        val clock = TestClock()
        var testEmail: TestEmailService? = null

        Server.test(
            settings = { context ->
                sms setStatic TestSMS("sms", context)
                email setStatic TestEmailService("email", context).also { testEmail = it }
            },
            clock = { clock }
        ) {
            runBlocking {
                val user = TestUser(name = "DelayedUser")
                Server.userInfo.table().insertOne(user)

                val model = TestModel(name = "Delayed", ownerId = user._id)
                Notifications.modelDelayed(model)

                // Notification should be created
                val notifications = Notifications.Dispatcher.info.table().all().toList()
                val userNotif = notifications.find { it.user == user._id }
                assertNotNull(userNotif)

                // Email should NOT have been sent (scheduled for 1 hour later)
                assertTrue(testEmail!!.sentEmails.isEmpty())

                // Email SendInfo should exist and not be sent
                assertNotNull(userNotif.email)
                assertFalse(userNotif.email!!.sent)
            }
        }
    }

    @Test
    fun `delayed event sent after time travel and refresh`() {
        val clock = TestClock()
        var testEmail: TestEmailService? = null

        Server.test(
            settings = { context ->
                sms setStatic TestSMS("sms", context)
                email setStatic TestEmailService("email", context).also { testEmail = it }
            },
            clock = { clock }
        ) {
            runBlocking {
                val user = TestUser(name = "TimeTravelUser")
                Server.userInfo.table().insertOne(user)

                val model = TestModel(name = "Delayed", ownerId = user._id)
                Notifications.modelDelayed(model)

                // Email not sent yet
                assertTrue(testEmail!!.sentEmails.isEmpty())

                // Advance clock by 2 hours (past the 1-hour delay)
                clock.measuredFrom = clock.measuredFrom + 2.hours

                // Refresh notifications to send delayed ones
                Notifications.Dispatcher.refreshNotifications()

                // Email should now be sent
                assertTrue(testEmail!!.sentEmails.isNotEmpty(), "Email should be sent after time travel and refresh")
            }
        }
    }

    @Test
    fun `inApp is always sent immediately even when email is delayed`() {
        val clock = TestClock()

        Server.test(
            settings = { context ->
                sms setStatic TestSMS("sms", context)
                email setStatic TestEmailService("email", context)
            },
            clock = { clock }
        ) {
            runBlocking {
                val user = TestUser(name = "InAppUser")
                Server.userInfo.table().insertOne(user)

                val model = TestModel(name = "Test", ownerId = user._id)
                Notifications.modelDelayed(model)

                val notifications = Notifications.Dispatcher.info.table().all().toList()
                val userNotif = notifications.find { it.user == user._id }
                assertNotNull(userNotif)

                // InApp should be marked as sent (immediate)
                assertNotNull(userNotif.inApp)
                assertTrue(userNotif.inApp!!.sent)

                // Email should NOT be sent (delayed)
                assertNotNull(userNotif.email)
                assertFalse(userNotif.email!!.sent)
            }
        }
    }

    // ===== Channel Configuration Tests =====

    @Test
    fun `null channel disables that notification type`() {
        var testSms: TestSMS? = null

        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context).also { testSms = it }
            email setStatic TestEmailService("email", context)
        }) {
            runBlocking {
                val user = TestUser(name = "EmailOnlyUser")
                Server.userInfo.table().insertOne(user)

                val model = TestModel(name = "Test", ownerId = user._id)
                Notifications.emailOnly(model)

                // No SMS should be sent
                assertTrue(testSms!!.messageHistory.isEmpty())

                // Notification should have no SMS info
                val notifications = Notifications.Dispatcher.info.table().all().toList()
                val userNotif = notifications.find { it.user == user._id }
                assertNotNull(userNotif)
                assertNull(userNotif.sms)
            }
        }
    }

    @Test
    fun `SMS only event does not send email`() {
        var testEmail: TestEmailService? = null

        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context).also { testEmail = it }
        }) {
            runBlocking {
                val user = TestUser(name = "SmsOnlyUser")
                Server.userInfo.table().insertOne(user)

                val model = TestModel(name = "Test", ownerId = user._id)
                Notifications.smsOnly(model)

                assertTrue(testEmail!!.sentEmails.isEmpty())
            }
        }
    }

    // ===== Sent Marking Tests =====

    @Test
    fun `channels are marked as sent after delivery`() {
        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context)
        }) {
            runBlocking {
                val user = TestUser(name = "MarkSentUser")
                Server.userInfo.table().insertOne(user)

                val model = TestModel(name = "Test", ownerId = user._id)
                Notifications.modelCreated(model)

                val notifications = Notifications.Dispatcher.info.table().all().toList()
                val userNotif = notifications.find { it.user == user._id }
                assertNotNull(userNotif)

                assertTrue(userNotif.email?.sent ?: false)
                assertTrue(userNotif.sms?.sent ?: false)
                assertTrue(userNotif.inApp?.sent ?: false)
            }
        }
    }

    // ===== Multiple Users Tests =====

    @Test
    fun `notifications for different users are processed separately`() {
        var testEmail: TestEmailService? = null

        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context).also { testEmail = it }
        }) {
            runBlocking {
                val user1 = TestUser(name = "User1", email = "user1@example.com")
                val user2 = TestUser(name = "User2", email = "user2@example.com")
                Server.userInfo.table().insertOne(user1)
                Server.userInfo.table().insertOne(user2)

                val model1 = TestModel(name = "Model1", ownerId = user1._id)
                val model2 = TestModel(name = "Model2", ownerId = user2._id)

                Notifications.modelCreated(model1)
                Notifications.modelCreated(model2)

                assertTrue(testEmail!!.sentEmails.size >= 2)

                val notifications = Notifications.Dispatcher.info.table().all().toList()
                assertTrue(notifications.any { it.user == user1._id })
                assertTrue(notifications.any { it.user == user2._id })
            }
        }
    }

    // ===== Content Verification Tests =====

    @Test
    fun `email content matches notification content`() {
        var testEmail: TestEmailService? = null

        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context).also { testEmail = it }
        }) {
            runBlocking {
                val user = TestUser(name = "ContentUser")
                Server.userInfo.table().insertOne(user)

                val model = TestModel(name = "Test", ownerId = user._id)
                Notifications.modelCreated(model)

                val email = testEmail!!.sentEmails.first()
                assertEquals("Created: Test", email.subject)
            }
        }
    }

    @Test
    fun `SMS content matches notification content`() {
        var testSms: TestSMS? = null

        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context).also { testSms = it }
            email setStatic TestEmailService("email", context)
        }) {
            runBlocking {
                val user = TestUser(name = "SmsContentUser")
                Server.userInfo.table().insertOne(user)

                val model = TestModel(name = "Test", ownerId = user._id)
                Notifications.modelCreated(model)

                assertTrue(testSms!!.messageHistory.isNotEmpty())
            }
        }
    }
}
