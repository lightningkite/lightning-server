// by Claude
package com.lightningkite.lightningserver.notifications.subscriptions

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.notifications.*
import com.lightningkite.lightningserver.notifications.events.*
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.settings.setStatic
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.*
import com.lightningkite.services.email.EmailService
import com.lightningkite.services.email.TestEmailService
import com.lightningkite.services.sms.SMS
import com.lightningkite.services.sms.TestSMS
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import kotlin.uuid.Uuid

/**
 * Tests for [FullyCustomizableSubscriptions].
 * Verifies fully customizable subscriptions with user filters work correctly.
 */
class FullyCustomizableSubscriptionsTest {

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

        val subs = FullyCustomizableSubscriptionsWithAuth(
            info = Server.database.testModelInfo<NotificationEventSubscription<Uuid>, UserEventType<Uuid>>(),
            users = Server.userInfo,
            principal = TestUser,
            suppressRejectedAuthenticationWarnings = true
        )

        val handler = path include NotificationEndpoints(
            Server.userInfo,
            Dispatcher,
            subs,
        )

        // Event with Condition.Always filter and UpdateReadPermissions behavior
        val modelCreated = handler.event("Model Created", Server.modelInfo) { notif ->
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

        // Event with ReplaceExistingWithDefault behavior
        val modelDeleted = handler.event("Model Deleted", Server.modelInfo) { notif ->
            notif.defaultSubscription(
                behavior = DefaultSubscriptionUpdateBehavior.ReplaceExistingWithDefault
            ) { user ->
                FullEventSubscription(
                    filter = Condition.Always,
                    email = Frequency.immediately(),
                    push = Frequency.immediately(),
                    sms = Frequency.immediately(),
                    inApp = Frequency.immediately()
                )
            }
            notif.content { event ->
                { user -> "Deleted: ${event.subject.name}" }
            }
        }
    }

    // ===== Default Subscription Creation Tests =====

    @Test
    fun `default subscription created for new user`() {
        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context)
        }) {
            runBlocking {
                val user = TestUser(name = "NewUser")
                Server.userInfo.table().insertOne(user)

                val eventType = Notifications.modelCreated.event.untyped
                val subscriptionId = UserEventType(user._id, eventType.name)
                val subscription = Notifications.subs.info.table().get(subscriptionId)

                assertNotNull(subscription)
                assertEquals(user._id, subscription._id.user)
                assertNotNull(subscription.email)
            }
        }
    }

    @Test
    fun `user deletion removes subscriptions`() {
        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context)
        }) {
            runBlocking {
                val user = TestUser(name = "ToBeDeleted")
                Server.userInfo.table().insertOne(user)

                val eventType = Notifications.modelCreated.event.untyped
                val subscriptionId = UserEventType(user._id, eventType.name)
                assertNotNull(Notifications.subs.info.table().get(subscriptionId))

                Server.userInfo.table().deleteOneById(user._id)

                assertNull(Notifications.subs.info.table().get(subscriptionId))
            }
        }
    }

    @Test
    fun `default subscriptions created for all event types`() {
        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context)
        }) {
            runBlocking {
                val user = TestUser(name = "AllEventsUser")
                Server.userInfo.table().insertOne(user)

                val createdSub = Notifications.subs.info.table()
                    .get(UserEventType(user._id, Notifications.modelCreated.event.name))
                val deletedSub = Notifications.subs.info.table()
                    .get(UserEventType(user._id, Notifications.modelDeleted.event.name))

                assertNotNull(createdSub)
                assertNotNull(deletedSub)
            }
        }
    }

    // ===== Filter Condition Tests =====

    @Test
    fun `Condition Always receives all events`() {
        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context)
        }) {
            runBlocking {
                val user = TestUser(name = "AlwaysUser")
                Server.userInfo.table().insertOne(user)

                val eventDef = Notifications.modelCreated.event

                // Any model should match since filter is Condition.Always
                val model = TestModel(name = "AnyModel", ownerId = user._id)
                val event = Event(eventDef, model)
                val subs = Notifications.handler.subscriptions.subscribed(event)

                assertTrue(subs.any { it.user == user._id })
            }
        }
    }

    // ===== User Preference Override Tests =====

    @Test
    fun `user can disable notification channel`() {
        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context)
        }) {
            runBlocking {
                val user = TestUser(name = "DisableChannelUser")
                Server.userInfo.table().insertOne(user)

                val eventDef = Notifications.modelCreated.event
                val subscriptionId = UserEventType(user._id, eventDef.name)

                val existing = Notifications.subs.info.table().get(subscriptionId)
                assertNotNull(existing)

                Notifications.subs.info.table().replaceOneById(
                    subscriptionId,
                    existing.copy(email = null)
                )

                val updated = Notifications.subs.info.table().get(subscriptionId)
                assertNotNull(updated)
                assertNull(updated.email)
            }
        }
    }

    @Test
    fun `user can change delivery frequency`() {
        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context)
        }) {
            runBlocking {
                val user = TestUser(name = "FrequencyChangeUser")
                Server.userInfo.table().insertOne(user)

                val eventDef = Notifications.modelCreated.event
                val subscriptionId = UserEventType(user._id, eventDef.name)

                val existing = Notifications.subs.info.table().get(subscriptionId)
                assertNotNull(existing)
                assertEquals(Frequency.immediately(), existing.email)

                val weekly = Frequency.weekly(kotlinx.datetime.DayOfWeek.FRIDAY, 10, 0, kotlinx.datetime.TimeZone.UTC)
                Notifications.subs.info.table().replaceOneById(
                    subscriptionId,
                    existing.copy(email = weekly)
                )

                val updated = Notifications.subs.info.table().get(subscriptionId)
                assertNotNull(updated)
                assertEquals(weekly, updated.email)
            }
        }
    }

    // ===== Multiple Users Tests =====

    @Test
    fun `multiple users receive independent subscriptions`() {
        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context)
        }) {
            runBlocking {
                val user1 = TestUser(name = "User1")
                val user2 = TestUser(name = "User2")
                Server.userInfo.table().insertOne(user1)
                Server.userInfo.table().insertOne(user2)

                val eventType = Notifications.modelCreated.event.untyped

                val sub1 = Notifications.subs.info.table()
                    .get(UserEventType(user1._id, eventType.name))
                val sub2 = Notifications.subs.info.table()
                    .get(UserEventType(user2._id, eventType.name))

                assertNotNull(sub1)
                assertNotNull(sub2)
                assertEquals(user1._id, sub1._id.user)
                assertEquals(user2._id, sub2._id.user)
            }
        }
    }

    @Test
    fun `deleting one user does not affect others`() {
        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context)
        }) {
            runBlocking {
                val user1 = TestUser(name = "User1ToDelete")
                val user2 = TestUser(name = "User2ToKeep")
                Server.userInfo.table().insertOne(user1)
                Server.userInfo.table().insertOne(user2)

                val eventType = Notifications.modelCreated.event.untyped

                Server.userInfo.table().deleteOneById(user1._id)

                assertNull(
                    Notifications.subs.info.table()
                        .get(UserEventType(user1._id, eventType.name))
                )
                assertNotNull(
                    Notifications.subs.info.table()
                        .get(UserEventType(user2._id, eventType.name))
                )
            }
        }
    }

    // ===== Subscription Update Behavior Tests =====

    @Test
    fun `UpdateReadPermissions preserves user frequency changes`() {
        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context)
        }) {
            runBlocking {
                val user = TestUser(name = "PermissionsUser")
                Server.userInfo.table().insertOne(user)

                val eventDef = Notifications.modelCreated.event
                val subscriptionId = UserEventType(user._id, eventDef.untyped.name)

                val existing = Notifications.subs.info.table().get(subscriptionId)
                assertNotNull(existing)

                val dailyFreq = Frequency.daily(10, 0, kotlinx.datetime.TimeZone.UTC)
                Notifications.subs.info.table().replaceOneById(
                    subscriptionId,
                    existing.copy(email = dailyFreq)
                )

                // Update user (triggers UpdateReadPermissions)
                val updatedUser = user.copy(name = "UpdatedName")
                Server.userInfo.table().replaceOneById(user._id, updatedUser)

                // User's custom email frequency should be preserved
                val afterUpdate = Notifications.subs.info.table().get(subscriptionId)
                assertNotNull(afterUpdate)
                assertEquals(dailyFreq, afterUpdate.email)
            }
        }
    }

    @Test
    fun `ReplaceExistingWithDefault replaces user changes`() {
        Server.test(settings = { context ->
            sms setStatic TestSMS("sms", context)
            email setStatic TestEmailService("email", context)
        }) {
            runBlocking {
                val user = TestUser(name = "ReplaceUser")
                Server.userInfo.table().insertOne(user)

                val eventDef = Notifications.modelDeleted.event
                val subscriptionId = UserEventType(user._id, eventDef.name)

                val existing = Notifications.subs.info.table().get(subscriptionId)
                assertNotNull(existing)

                Notifications.subs.info.table().replaceOneById(
                    subscriptionId,
                    existing.copy(
                        email = Frequency.weekly(
                            kotlinx.datetime.DayOfWeek.MONDAY,
                            9,
                            0,
                            kotlinx.datetime.TimeZone.UTC
                        )
                    )
                )

                // Verify customization
                val customized = Notifications.subs.info.table().get(subscriptionId)
                assertEquals(
                    Frequency.weekly(kotlinx.datetime.DayOfWeek.MONDAY, 9, 0, kotlinx.datetime.TimeZone.UTC),
                    customized?.email
                )

                // Update user (triggers ReplaceExistingWithDefault)
                val updatedUser = user.copy(name = "UpdatedName")
                Server.userInfo.table().replaceOneById(user._id, updatedUser)

                // User's changes should be replaced with default
                val afterUpdate = Notifications.subs.info.table().get(subscriptionId)
                assertNotNull(afterUpdate)
                assertEquals(Frequency.immediately(), afterUpdate.email)
            }
        }
    }
}
