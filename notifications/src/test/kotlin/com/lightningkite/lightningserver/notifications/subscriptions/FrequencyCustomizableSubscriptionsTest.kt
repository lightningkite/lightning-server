// by Claude
package com.lightningkite.lightningserver.notifications.subscriptions

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.notifications.*
import com.lightningkite.lightningserver.notifications.events.*
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
 * Tests for [FrequencyCustomizableSubscriptions].
 * Verifies frequency-only customization works correctly.
 */
class FrequencyCustomizableSubscriptionsTest {

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

        val subs = FrequencyCustomizableSubscriptions<TestUser, Uuid>(
            info = Server.database.testModelInfo()
        )

        val handler = path include NotificationEndpoints(
            Server.userInfo,
            Dispatcher,
            subs
        )

        // Event with immediate defaults
        val modelCreated = handler.event("Model Created", Server.modelInfo) { notif ->
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

        // Event with daily defaults
        val modelDeleted = handler.event("Model Deleted", Server.modelInfo) { notif ->
            notif.subscribed(
                defaultEmail = Frequency.daily(9, 0, kotlinx.datetime.TimeZone.UTC),
                defaultSms = Frequency.daily(9, 0, kotlinx.datetime.TimeZone.UTC),
                defaultPush = Frequency.immediately(),
                defaultInApp = Frequency.immediately()
            ) { event ->
                setOf(event.subject.ownerId)
            }
            notif.content { event ->
                { user -> "Deleted: ${event.subject.name}" }
            }
        }
    }

    // ===== Default Frequency Tests =====

    @Test
    fun `default frequencies used when no user override exists`() {
        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context)
        }) {
            runBlocking {
                val user = TestUser(name = "DefaultUser")
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

    // ===== User Override Tests =====

    @Test
    fun `user override replaces default frequencies`() {
        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context)
        }) {
            runBlocking {
                val user = TestUser(name = "OverrideUser")
                Server.userInfo.table().insertOne(user)

                val eventDef = Notifications.modelCreated.event

                // Insert user override
                Notifications.subs.info.table().insertOne(
                    NotificationSendMethods(
                        _id = UserEventType(user._id, eventDef.name),
                        email = Frequency.daily(9, 0, kotlinx.datetime.TimeZone.UTC),
                        sms = null, // Disable SMS
                        push = Frequency.immediately(),
                        inApp = Frequency.immediately()
                    )
                )

                val model = TestModel(ownerId = user._id)
                val event = Event(eventDef, model)

                val subscriptions = Notifications.handler.subscriptions.subscribed(event)
                val sub = subscriptions.find { it.user == user._id }!!

                assertEquals(Frequency.daily(9, 0, kotlinx.datetime.TimeZone.UTC), sub.email)
                assertNull(sub.sms)
                assertEquals(Frequency.immediately(), sub.push)
            }
        }
    }

    @Test
    fun `user can disable specific channels`() {
        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context)
        }) {
            runBlocking {
                val user = TestUser(name = "DisableUser")
                Server.userInfo.table().insertOne(user)

                val eventDef = Notifications.modelCreated.event

                // User disables all channels except inApp
                Notifications.subs.info.table().insertOne(
                    NotificationSendMethods(
                        _id = UserEventType(user._id, eventDef.name),
                        email = null,
                        sms = null,
                        push = null,
                        inApp = Frequency.immediately()
                    )
                )

                val model = TestModel(ownerId = user._id)
                val event = Event(eventDef, model)

                val subscriptions = Notifications.handler.subscriptions.subscribed(event)
                val sub = subscriptions.find { it.user == user._id }!!

                assertNull(sub.email)
                assertNull(sub.sms)
                assertNull(sub.push)
                assertEquals(Frequency.immediately(), sub.inApp)
            }
        }
    }

    @Test
    fun `different events have different defaults`() {
        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context)
        }) {
            runBlocking {
                val user = TestUser(name = "DailyUser")
                Server.userInfo.table().insertOne(user)

                // modelDeleted configured with daily defaults
                val deletedEvent = Event(
                    Notifications.modelDeleted.event,
                    TestModel(ownerId = user._id)
                )
                val deletedSubs = Notifications.handler.subscriptions.subscribed(deletedEvent)
                val deletedSub = deletedSubs.find { it.user == user._id }!!

                assertEquals(Frequency.daily(9, 0, kotlinx.datetime.TimeZone.UTC), deletedSub.email)
                assertEquals(Frequency.daily(9, 0, kotlinx.datetime.TimeZone.UTC), deletedSub.sms)
                assertEquals(Frequency.immediately(), deletedSub.push)

                // modelCreated configured with immediate defaults
                val createdEvent = Event(
                    Notifications.modelCreated.event,
                    TestModel(ownerId = user._id)
                )
                val createdSubs = Notifications.handler.subscriptions.subscribed(createdEvent)
                val createdSub = createdSubs.find { it.user == user._id }!!

                assertEquals(Frequency.immediately(), createdSub.email)
            }
        }
    }

    @Test
    fun `user override on one event does not affect another`() {
        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context)
        }) {
            runBlocking {
                val user = TestUser(name = "MultiEventUser")
                Server.userInfo.table().insertOne(user)

                // Override only for modelDeleted
                Notifications.subs.info.table().insertOne(
                    NotificationSendMethods(
                        _id = UserEventType(user._id, Notifications.modelDeleted.event.name),
                        email = Frequency.weekly(
                            kotlinx.datetime.DayOfWeek.FRIDAY,
                            10,
                            0,
                            kotlinx.datetime.TimeZone.UTC
                        ),
                        sms = null,
                        push = Frequency.immediately(),
                        inApp = Frequency.immediately()
                    )
                )

                // modelCreated should still use defaults
                val createdEvent = Event(
                    Notifications.modelCreated.event,
                    TestModel(ownerId = user._id)
                )
                val createdSub = Notifications.handler.subscriptions.subscribed(createdEvent)
                    .find { it.user == user._id }!!
                assertEquals(Frequency.immediately(), createdSub.email)

                // modelDeleted uses override
                val deletedEvent = Event(
                    Notifications.modelDeleted.event,
                    TestModel(ownerId = user._id)
                )
                val deletedSub = Notifications.handler.subscriptions.subscribed(deletedEvent)
                    .find { it.user == user._id }!!
                assertEquals(
                    Frequency.weekly(kotlinx.datetime.DayOfWeek.FRIDAY, 10, 0, kotlinx.datetime.TimeZone.UTC),
                    deletedSub.email
                )
            }
        }
    }
}
