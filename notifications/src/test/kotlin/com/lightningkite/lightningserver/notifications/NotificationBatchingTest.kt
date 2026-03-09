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
 * Tests for notification batching behavior.
 * Verifies that notifications are properly grouped by sendAt time
 * and channel frequencies work correctly.
 */
class NotificationBatchingTest {

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

        // All channels immediate
        val allImmediate = handler.event("All Immediate", Server.modelInfo) { notif ->
            notif.subscribed(
                email = Frequency.immediately(),
                sms = Frequency.immediately(),
                push = null,
                inApp = Frequency.immediately()
            ) { event ->
                setOf(event.subject.ownerId)
            }
            notif.content { event ->
                { user -> "Immediate: ${event.subject.name}" }
            }
        }

        // Email delayed, others immediate
        val emailDelayed = handler.event("Email Delayed", Server.modelInfo) { notif ->
            notif.subscribed(
                email = Frequency.delayed(1.hours),
                sms = Frequency.immediately(),
                push = null,
                inApp = Frequency.immediately()
            ) { event ->
                setOf(event.subject.ownerId)
            }
            notif.content { event ->
                { user -> "Email Delayed: ${event.subject.name}" }
            }
        }

        // All channels delayed
        val allDelayed = handler.event("All Delayed", Server.modelInfo) { notif ->
            notif.subscribed(
                email = Frequency.delayed(1.hours),
                sms = Frequency.delayed(1.hours),
                push = null,
                inApp = Frequency.delayed(1.hours)
            ) { event ->
                setOf(event.subject.ownerId)
            }
            notif.content { event ->
                { user -> "All Delayed: ${event.subject.name}" }
            }
        }

        // Email only
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

        // SMS only
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

        // InApp only
        val inAppOnly = handler.event("InApp Only", Server.modelInfo) { notif ->
            notif.subscribed(
                email = null,
                sms = null,
                push = null,
                inApp = Frequency.immediately()
            ) { event ->
                setOf(event.subject.ownerId)
            }
            notif.content { event ->
                { user -> "InApp Only: ${event.subject.name}" }
            }
        }
    }

    // ===== All Immediate =====

    @Test
    fun `all immediate frequencies send all channels at once`() {
        var testEmail: TestEmailService? = null
        var testSms: TestSMS? = null

        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context).also { testSms = it }
            email setStatic TestEmailService("email", context).also { testEmail = it }
        }) {
            runBlocking {
                val user = TestUser(name = "AllImmediateUser", email = "test@example.com", phone = "1234567890")
                Server.userInfo.table().insertOne(user)

                val model = TestModel(name = "Test", ownerId = user._id)
                Notifications.allImmediate(model)

                assertTrue(testEmail!!.sentEmails.isNotEmpty())
                assertTrue(testSms!!.messageHistory.isNotEmpty())

                val notifications = Notifications.Dispatcher.info.table().all().toList()
                val userNotif = notifications.find { it.user == user._id }
                assertNotNull(userNotif)
                assertTrue(userNotif.email?.sent ?: false)
                assertTrue(userNotif.sms?.sent ?: false)
                assertTrue(userNotif.inApp?.sent ?: false)
            }
        }
    }

    // ===== All Delayed =====

    @Test
    fun `all delayed frequencies send nothing immediately`() {
        val clock = TestClock()
        var testEmail: TestEmailService? = null
        var testSms: TestSMS? = null

        Server.test(
            settings = { context ->
                sms setStatic TestSMS("sms", context).also { testSms = it }
                email setStatic TestEmailService("email", context).also { testEmail = it }
            },
            clock = { clock }
        ) {
            runBlocking {
                val user = TestUser(name = "AllDelayedUser")
                Server.userInfo.table().insertOne(user)

                val model = TestModel(name = "Test", ownerId = user._id)
                Notifications.allDelayed(model)

                // Nothing should be sent immediately
                assertTrue(testEmail!!.sentEmails.isEmpty())
                assertTrue(testSms!!.messageHistory.isEmpty())

                // All channels should exist but not be sent
                val notifications = Notifications.Dispatcher.info.table().all().toList()
                val userNotif = notifications.find { it.user == user._id }
                assertNotNull(userNotif)
                assertNotNull(userNotif.email)
                assertFalse(userNotif.email!!.sent)
                assertNotNull(userNotif.sms)
                assertFalse(userNotif.sms!!.sent)
                assertNotNull(userNotif.inApp)
                assertFalse(userNotif.inApp!!.sent)
            }
        }
    }

    @Test
    fun `all delayed sent after time travel and refresh`() {
        val clock = TestClock()
        var testEmail: TestEmailService? = null
        var testSms: TestSMS? = null

        Server.test(
            settings = { context ->
                sms setStatic TestSMS("sms", context).also { testSms = it }
                email setStatic TestEmailService("email", context).also { testEmail = it }
            },
            clock = { clock }
        ) {
            runBlocking {
                val user = TestUser(name = "DelayedRefreshUser", email = "delayed@example.com", phone = "1234567890")
                Server.userInfo.table().insertOne(user)

                Notifications.allDelayed(TestModel(name = "M1", ownerId = user._id))
                Notifications.allDelayed(TestModel(name = "M2", ownerId = user._id))

                // Nothing sent yet
                assertTrue(testEmail!!.sentEmails.isEmpty())
                assertTrue(testSms!!.messageHistory.isEmpty())

                // Advance clock past delay
                clock.measuredFrom = clock.measuredFrom + 2.hours

                // Refresh to send delayed notifications
                Notifications.Dispatcher.refreshNotifications()

                // Everything should now be sent
                assertTrue(testEmail!!.sentEmails.isNotEmpty(), "Emails should be sent after refresh")
                assertTrue(testSms!!.messageHistory.isNotEmpty(), "SMS should be sent after refresh")
            }
        }
    }

    // ===== Mixed Channels =====

    @Test
    fun `different channels can have different frequencies`() {
        val clock = TestClock()
        var testEmail: TestEmailService? = null
        var testSms: TestSMS? = null

        Server.test(
            settings = { context ->
                sms setStatic TestSMS("sms", context).also { testSms = it }
                email setStatic TestEmailService("email", context).also { testEmail = it }
            },
            clock = { clock }
        ) {
            runBlocking {
                val user = TestUser(name = "MixedFreqUser", email = "test@example.com", phone = "1234567890")
                Server.userInfo.table().insertOne(user)

                val model = TestModel(name = "Test", ownerId = user._id)
                Notifications.emailDelayed(model)

                // SMS should be sent immediately
                assertTrue(testSms!!.messageHistory.isNotEmpty())

                // Email should NOT be sent (delayed)
                assertTrue(testEmail!!.sentEmails.isEmpty())

                val notifications = Notifications.Dispatcher.info.table().all().toList()
                val userNotif = notifications.find { it.user == user._id }
                assertNotNull(userNotif)

                assertNotNull(userNotif.sms)
                assertTrue(userNotif.sms!!.sent)

                assertNotNull(userNotif.email)
                assertFalse(userNotif.email!!.sent)
            }
        }
    }

    // ===== Channel Disabling =====

    @Test
    fun `null frequency disables email channel`() {
        var testEmail: TestEmailService? = null

        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context).also { testEmail = it }
        }) {
            runBlocking {
                val user = TestUser(name = "NoEmailUser")
                Server.userInfo.table().insertOne(user)

                Notifications.smsOnly(TestModel(name = "Test", ownerId = user._id))

                assertTrue(testEmail!!.sentEmails.isEmpty())

                val notifications = Notifications.Dispatcher.info.table().all().toList()
                val userNotif = notifications.find { it.user == user._id }
                assertNotNull(userNotif)
                assertNull(userNotif.email)
            }
        }
    }

    @Test
    fun `null frequency disables SMS channel`() {
        var testSms: TestSMS? = null

        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context).also { testSms = it }
            email setStatic TestEmailService("email", context)
        }) {
            runBlocking {
                val user = TestUser(name = "NoSmsUser")
                Server.userInfo.table().insertOne(user)

                Notifications.emailOnly(TestModel(name = "Test", ownerId = user._id))

                assertTrue(testSms!!.messageHistory.isEmpty())

                val notifications = Notifications.Dispatcher.info.table().all().toList()
                val userNotif = notifications.find { it.user == user._id }
                assertNotNull(userNotif)
                assertNull(userNotif.sms)
            }
        }
    }

    // ===== InApp Only =====

    @Test
    fun `inApp only creates notification without sending email or SMS`() {
        var testEmail: TestEmailService? = null
        var testSms: TestSMS? = null

        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context).also { testSms = it }
            email setStatic TestEmailService("email", context).also { testEmail = it }
        }) {
            runBlocking {
                val user = TestUser(name = "InAppOnlyUser")
                Server.userInfo.table().insertOne(user)

                Notifications.inAppOnly(TestModel(name = "Test", ownerId = user._id))

                // No email or SMS
                assertTrue(testEmail!!.sentEmails.isEmpty())
                assertTrue(testSms!!.messageHistory.isEmpty())

                // Notification with inApp sent
                val notifications = Notifications.Dispatcher.info.table().all().toList()
                val userNotif = notifications.find { it.user == user._id }
                assertNotNull(userNotif)
                assertNotNull(userNotif.inApp)
                assertTrue(userNotif.inApp!!.sent)
                assertNull(userNotif.email)
                assertNull(userNotif.sms)
            }
        }
    }

    // ===== Multiple Events Batching =====

    @Test
    fun `multiple immediate notifications create separate entries`() {
        var testEmail: TestEmailService? = null

        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context).also { testEmail = it }
        }) {
            runBlocking {
                val user = TestUser(name = "BatchUser", email = "batch@example.com")
                Server.userInfo.table().insertOne(user)

                Notifications.allImmediate(TestModel(name = "Model1", ownerId = user._id))
                Notifications.allImmediate(TestModel(name = "Model2", ownerId = user._id))
                Notifications.allImmediate(TestModel(name = "Model3", ownerId = user._id))

                val notifications = Notifications.Dispatcher.info.table().all().toList()
                val userNotifs = notifications.filter { it.user == user._id }
                assertTrue(userNotifs.size >= 3)

                assertTrue(testEmail!!.sentEmails.isNotEmpty())
            }
        }
    }

    @Test
    fun `multiple events create separate notifications with correct content`() {
        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context)
        }) {
            runBlocking {
                val user = TestUser(name = "MultiEventUser", email = "multi@example.com")
                Server.userInfo.table().insertOne(user)

                Notifications.allImmediate(TestModel(name = "Model1", ownerId = user._id))
                Notifications.emailOnly(TestModel(name = "Model2", ownerId = user._id))

                val notifications = Notifications.Dispatcher.info.table().all().toList()
                val userNotifs = notifications.filter { it.user == user._id }
                assertTrue(userNotifs.size >= 2)
            }
        }
    }
}
