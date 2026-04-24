// by Claude
package com.lightningkite.lightningserver.notifications.subscriptions

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.notifications.*
import com.lightningkite.lightningserver.notifications.events.Event
import com.lightningkite.lightningserver.notifications.events.event
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.settings.setStatic
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.insertOne
import com.lightningkite.services.email.EmailService
import com.lightningkite.services.email.TestEmailService
import com.lightningkite.services.sms.SMS
import com.lightningkite.services.sms.TestSMS
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import kotlin.uuid.Uuid

/**
 * Tests for [NonCustomizableSubscriptions].
 * Verifies programmatic-only subscriptions work correctly.
 */
class NonCustomizableSubscriptionsTest {

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

        // Event that notifies model owner - for isolated testing
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

        val modelDeleted = handler.event("Model Deleted", Server.modelInfo) { notif ->
            notif.subscribed(
                email = Frequency.daily(9, 0, kotlinx.datetime.TimeZone.UTC),
                sms = null,
                push = Frequency.immediately(),
                inApp = Frequency.immediately()
            ) { event ->
                setOf(event.subject.ownerId)
            }
            notif.content { event ->
                { user -> "Deleted: ${event.subject.name}" }
            }
        }
    }

    @Test
    fun `subscribed returns correct subscriber for owner event`() {
        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context)
        }) {
            runBlocking {
                val user = TestUser(name = "TestUser")
                Server.userInfo.table().insertOne(user)

                val model = TestModel(ownerId = user._id)
                val event = Event(Notifications.modelCreated.event, model)

                val subscriptions = Notifications.handler.subscriptions.subscribed(event)

                assertEquals(1, subscriptions.size)
                assertEquals(user._id, subscriptions.first().user)
            }
        }
    }

    @Test
    fun `event subscriber has correct frequencies`() {
        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context)
        }) {
            runBlocking {
                val user = TestUser(name = "FrequencyUser")
                Server.userInfo.table().insertOne(user)

                val model = TestModel(ownerId = user._id)
                val event = Event(Notifications.modelCreated.event, model)

                val subscriptions = Notifications.handler.subscriptions.subscribed(event)
                val sub = subscriptions.find { it.user == user._id }!!

                assertEquals(Frequency.immediately(), sub.email)
                assertEquals(Frequency.immediately(), sub.sms)
                assertNull(sub.push)
                assertEquals(Frequency.immediately(), sub.inApp)
            }
        }
    }

    @Test
    fun `modelDeleted only notifies owner`() {
        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context)
        }) {
            runBlocking {
                val owner = TestUser(name = "Owner")
                val other = TestUser(name = "Other")
                Server.userInfo.table().insertOne(owner)
                Server.userInfo.table().insertOne(other)

                val model = TestModel(ownerId = owner._id)
                val event = Event(Notifications.modelDeleted.event, model)

                val subscriptions = Notifications.handler.subscriptions.subscribed(event)

                assertTrue(subscriptions.any { it.user == owner._id })
                assertTrue(subscriptions.none { it.user == other._id })
            }
        }
    }

    @Test
    fun `null frequency disables channel`() {
        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context)
        }) {
            runBlocking {
                val owner = TestUser(name = "Owner")
                Server.userInfo.table().insertOne(owner)

                val model = TestModel(ownerId = owner._id)
                val event = Event(Notifications.modelDeleted.event, model)

                val subs = Notifications.handler.subscriptions.subscribed(event)
                val sub = subs.find { it.user == owner._id }!!

                // modelDeleted has sms = null
                assertNull(sub.sms)
                assertNotNull(sub.email)
            }
        }
    }

    @Test
    fun `modelDeleted has daily frequency for email`() {
        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context)
        }) {
            runBlocking {
                val owner = TestUser(name = "Owner")
                Server.userInfo.table().insertOne(owner)

                val model = TestModel(ownerId = owner._id)
                val event = Event(Notifications.modelDeleted.event, model)

                val subs = Notifications.handler.subscriptions.subscribed(event)
                val sub = subs.find { it.user == owner._id }!!

                assertEquals(Frequency.daily(9, 0, kotlinx.datetime.TimeZone.UTC), sub.email)
                assertEquals(Frequency.immediately(), sub.push)
            }
        }
    }
}
