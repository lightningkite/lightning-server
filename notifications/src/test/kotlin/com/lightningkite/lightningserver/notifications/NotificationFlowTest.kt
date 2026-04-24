// by Claude
package com.lightningkite.lightningserver.notifications

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.notifications.events.UserEventType
import com.lightningkite.lightningserver.notifications.events.event
import com.lightningkite.lightningserver.notifications.subscriptions.*
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
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * End-to-end integration tests for the notification system.
 * Tests complete flows from event trigger to notification delivery
 * across different subscription provider types.
 */
class NotificationFlowTest {

    // ===== NonCustomizable Flow =====

    private object NonCustomServer : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val cache = setting("cache", Cache.Settings())
        val sms = setting("sms", SMS.Settings())
        val email = setting("email", EmailService.Settings())

        val userInfo = database.testModelInfo<TestUser, Uuid>()
        val modelInfo = database.testModelInfo<TestModel, Uuid>()

        val notifications = path.path("notifications") module NonCustomNotifications
    }

    private object NonCustomNotifications : ServerBuilder() {
        object Dispatcher : TestDispatcherBase(
            info = NonCustomServer.database.testModelInfo(),
            cache = NonCustomServer.cache,
            database = NonCustomServer.database,
            users = NonCustomServer.userInfo,
            email = NonCustomServer.email,
            sms = NonCustomServer.sms
        )

        val handler = path include NotificationEndpoints(
            NonCustomServer.userInfo,
            Dispatcher,
            NonCustomizableSubscriptions()
        )

        val modelCreated = handler.event("Model Created", NonCustomServer.modelInfo) { notif ->
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
    }

    @Test
    fun `complete flow with NonCustomizable subscriptions`() {
        var testEmail: TestEmailService? = null
        var testSms: TestSMS? = null

        NonCustomServer.test(settings = { context ->
            sms setStatic TestSMS("sms", context).also { testSms = it }
            email setStatic TestEmailService("email", context).also { testEmail = it }
        }) {
            runBlocking {
                val user = TestUser(name = "E2EUser", email = "e2e@example.com", phone = "1234567890")
                NonCustomServer.userInfo.table().insertOne(user)

                val model = TestModel(name = "E2EModel", ownerId = user._id)
                NonCustomNotifications.modelCreated(model)

                // Verify notification created
                val notifications = NonCustomNotifications.Dispatcher.info.table().all().toList()
                val userNotif = notifications.find { it.user == user._id }
                assertNotNull(userNotif)
                assertEquals("Created: E2EModel", userNotif.content)

                // Verify email sent
                assertTrue(testEmail!!.sentEmails.isNotEmpty())
                assertEquals("Created: E2EModel", testEmail.sentEmails.last().subject)

                // Verify SMS sent
                assertTrue(testSms!!.messageHistory.isNotEmpty())

                // Verify channels marked as sent
                assertTrue(userNotif.email?.sent ?: false)
                assertTrue(userNotif.sms?.sent ?: false)
                assertTrue(userNotif.inApp?.sent ?: false)
            }
        }
    }

    // ===== FrequencyCustomizable Flow =====

    private object FreqCustomServer : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val cache = setting("cache", Cache.Settings())
        val sms = setting("sms", SMS.Settings())
        val email = setting("email", EmailService.Settings())

        val userInfo = database.testModelInfo<TestUser, Uuid>()
        val modelInfo = database.testModelInfo<TestModel, Uuid>()

        val notifications = path.path("notifications") module FreqCustomNotifications
    }

    private object FreqCustomNotifications : ServerBuilder() {
        object Dispatcher : TestDispatcherBase(
            info = FreqCustomServer.database.testModelInfo(),
            cache = FreqCustomServer.cache,
            database = FreqCustomServer.database,
            users = FreqCustomServer.userInfo,
            email = FreqCustomServer.email,
            sms = FreqCustomServer.sms
        )

        val subs = FrequencyCustomizableSubscriptions<TestUser, Uuid>(
            info = FreqCustomServer.database.testModelInfo()
        )

        val handler = path include NotificationEndpoints(
            FreqCustomServer.userInfo,
            Dispatcher,
            subs
        )

        val modelCreated = handler.event("Model Created", FreqCustomServer.modelInfo) { notif ->
            notif.subscribed(
                defaultEmail = Frequency.immediately(),
                defaultSms = Frequency.immediately(),
                defaultPush = null,
                defaultInApp = Frequency.immediately()
            ) { event ->
                setOf(event.subject.ownerId)
            }
            notif.content { event ->
                { user -> "Created: ${event.subject.name}" }
            }
        }
    }

    @Test
    fun `complete flow with FrequencyCustomizable using defaults`() {
        var testEmail: TestEmailService? = null

        FreqCustomServer.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context).also { testEmail = it }
        }) {
            runBlocking {
                val user = TestUser(name = "FreqDefaultUser", email = "freq@example.com")
                FreqCustomServer.userInfo.table().insertOne(user)

                val model = TestModel(name = "FreqModel", ownerId = user._id)
                FreqCustomNotifications.modelCreated(model)

                val notifications = FreqCustomNotifications.Dispatcher.info.table().all().toList()
                val userNotif = notifications.find { it.user == user._id }
                assertNotNull(userNotif)

                assertTrue(testEmail!!.sentEmails.isNotEmpty())
            }
        }
    }

    @Test
    fun `complete flow with FrequencyCustomizable with user override`() {
        var testEmail: TestEmailService? = null

        FreqCustomServer.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context).also { testEmail = it }
        }) {
            runBlocking {
                val user = TestUser(name = "FreqOverrideUser", email = "override@example.com")
                FreqCustomServer.userInfo.table().insertOne(user)

                // Set user preference to disable email
                FreqCustomNotifications.subs.info.table().insertOne(
                    NotificationSendMethods(
                        _id = UserEventType(user._id, FreqCustomNotifications.modelCreated.event.name),
                        email = null, // Disable email
                        sms = Frequency.immediately(),
                        push = null,
                        inApp = Frequency.immediately()
                    )
                )

                val initialEmailCount = testEmail!!.sentEmails.size
                val model = TestModel(name = "OverrideModel", ownerId = user._id)
                FreqCustomNotifications.modelCreated(model)

                val notifications = FreqCustomNotifications.Dispatcher.info.table().all().toList()
                val userNotif = notifications.find { it.user == user._id }
                assertNotNull(userNotif)

                // Email should be disabled
                kotlin.test.assertNull(userNotif.email)

                // No additional email should have been sent
                assertEquals(initialEmailCount, testEmail.sentEmails.size)
            }
        }
    }

    // ===== FullyCustomizable Flow =====

    private object FullCustomServer : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val cache = setting("cache", Cache.Settings())
        val sms = setting("sms", SMS.Settings())
        val email = setting("email", EmailService.Settings())

        val userInfo = database.testModelInfo<TestUser, Uuid>()
        val modelInfo = database.testModelInfo<TestModel, Uuid>()

        val notifications = path.path("notifications") module FullCustomNotifications
    }

    private object FullCustomNotifications : ServerBuilder() {
        object Dispatcher : TestDispatcherBase(
            info = FullCustomServer.database.testModelInfo(),
            cache = FullCustomServer.cache,
            database = FullCustomServer.database,
            users = FullCustomServer.userInfo,
            email = FullCustomServer.email,
            sms = FullCustomServer.sms
        )

        val subs = FullyCustomizableSubscriptionsWithAuth(
            info = FullCustomServer.database.testModelInfo<NotificationEventSubscription<Uuid>, UserEventType<Uuid>>(),
            users = FullCustomServer.userInfo,
            principal = TestUser,
            suppressRejectedAuthenticationWarnings = true
        )

        val handler = path include NotificationEndpoints(
            FullCustomServer.userInfo,
            Dispatcher,
            subs,
        )

        val modelCreated = handler.event("Model Created", FullCustomServer.modelInfo) { notif ->
            notif.defaultSubscription(
                behavior = DefaultSubscriptionUpdateBehavior.UpdateReadPermissions
            ) { user ->
                FullEventSubscription(
                    filter = Condition.Always,
                    email = Frequency.immediately(),
                    push = null,
                    sms = Frequency.immediately(),
                    inApp = Frequency.immediately()
                )
            }
            notif.content { event ->
                { user -> "Created: ${event.subject.name}" }
            }
        }
    }

    @Test
    fun `complete flow with FullyCustomizable subscriptions`() {
        var testEmail: TestEmailService? = null

        FullCustomServer.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context).also { testEmail = it }
        }) {
            runBlocking {
                val user = TestUser(name = "FullCustomUser", email = "fullcustom@example.com")
                FullCustomServer.userInfo.table().insertOne(user)

                val model = TestModel(name = "FullCustomModel", ownerId = user._id)
                FullCustomNotifications.modelCreated(model)

                val notifications = FullCustomNotifications.Dispatcher.info.table().all().toList()
                val userNotif = notifications.find { it.user == user._id }
                assertNotNull(userNotif)
                assertEquals("Created: FullCustomModel", userNotif.content)

                assertTrue(testEmail!!.sentEmails.isNotEmpty())
            }
        }
    }

    // ===== No Subscribers =====

    @Test
    fun `event with no subscribers creates no notifications`() {
        // Reuse NonCustomServer but with a model owned by nobody in the user table
        var testEmail: TestEmailService? = null

        NonCustomServer.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context).also { testEmail = it }
        }) {
            runBlocking {
                // Don't insert any user - the owner ID won't match anyone
                val initialEmailCount = testEmail!!.sentEmails.size
                val model = TestModel(name = "NoSubModel", ownerId = Uuid.random())
                NonCustomNotifications.modelCreated(model)

                // The subscriber generator returns a set with the ownerId,
                // but since no user is found, no notification should be dispatched successfully
                assertEquals(initialEmailCount, testEmail.sentEmails.size)
            }
        }
    }

    // ===== Multiple Users Same Event =====

    @Test
    fun `multiple users receive notifications for same event trigger`() {
        var testEmail: TestEmailService? = null

        NonCustomServer.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context).also { testEmail = it }
        }) {
            runBlocking {
                val user1 = TestUser(name = "User1", email = "user1@example.com")
                val user2 = TestUser(name = "User2", email = "user2@example.com")
                NonCustomServer.userInfo.table().insertOne(user1)
                NonCustomServer.userInfo.table().insertOne(user2)

                NonCustomNotifications.modelCreated(TestModel(name = "M1", ownerId = user1._id))
                NonCustomNotifications.modelCreated(TestModel(name = "M2", ownerId = user2._id))

                val notifications = NonCustomNotifications.Dispatcher.info.table().all().toList()
                assertTrue(notifications.any { it.user == user1._id })
                assertTrue(notifications.any { it.user == user2._id })

                assertTrue(testEmail!!.sentEmails.size >= 2)
            }
        }
    }

    // ===== Time-Travel E2E: Mixed channels =====

    private object TimeTravelServer : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val cache = setting("cache", Cache.Settings())
        val sms = setting("sms", SMS.Settings())
        val email = setting("email", EmailService.Settings())

        val userInfo = database.testModelInfo<TestUser, Uuid>()
        val modelInfo = database.testModelInfo<TestModel, Uuid>()

        val notifications = path.path("notifications") module TimeTravelNotifications
    }

    private object TimeTravelNotifications : ServerBuilder() {
        object Dispatcher : TestDispatcherBase(
            info = TimeTravelServer.database.testModelInfo(),
            cache = TimeTravelServer.cache,
            database = TimeTravelServer.database,
            users = TimeTravelServer.userInfo,
            email = TimeTravelServer.email,
            sms = TimeTravelServer.sms
        )

        val handler = path include NotificationEndpoints(
            TimeTravelServer.userInfo,
            Dispatcher,
            NonCustomizableSubscriptions()
        )

        // email=immediate, sms=delayed
        val mixedChannels = handler.event("Mixed Channels", TimeTravelServer.modelInfo) { notif ->
            notif.subscribed(
                email = Frequency.immediately(),
                sms = Frequency.delayed(1.hours),
                push = null,
                inApp = Frequency.immediately()
            ) { event ->
                setOf(event.subject.ownerId)
            }
            notif.content { event ->
                { user -> "Mixed: ${event.subject.name}" }
            }
        }
    }

    @Test
    fun `time-travel E2E with mixed immediate and delayed channels`() {
        val clock = TestClock()
        var testSms: TestSMS? = null
        var testEmail: TestEmailService? = null

        TimeTravelServer.test(
            settings = { context ->
                sms setStatic TestSMS("sms", context).also { testSms = it }
                email setStatic TestEmailService("email", context).also { testEmail = it }
            },
            clock = { clock }
        ) {
            runBlocking {
                val user = TestUser(name = "MixedUser", phone = "1234567890", email = "mixed@example.com")
                TimeTravelServer.userInfo.table().insertOne(user)

                TimeTravelNotifications.mixedChannels(TestModel(name = "MixedModel", ownerId = user._id))

                // Email should be sent immediately
                assertTrue(testEmail!!.sentEmails.isNotEmpty(), "Email should send immediately")

                // SMS should NOT be sent yet (delayed 1 hour)
                assertTrue(testSms!!.messageHistory.isEmpty(), "SMS should not send immediately")

                // Verify notification state
                val notifications = TimeTravelNotifications.Dispatcher.info.table().all().toList()
                val userNotif = notifications.find { it.user == user._id }
                assertNotNull(userNotif)
                assertTrue(userNotif.email?.sent ?: false, "Email should be marked sent")
                assertFalse(userNotif.sms?.sent ?: true, "SMS should not be marked sent")

                // Time travel: advance 2 hours
                clock.measuredFrom = clock.measuredFrom + 2.hours

                // Refresh to send delayed notifications
                TimeTravelNotifications.Dispatcher.refreshNotifications()

                // SMS should now be sent
                assertTrue(testSms.messageHistory.isNotEmpty(), "SMS should send after time travel")
            }
        }
    }
}
