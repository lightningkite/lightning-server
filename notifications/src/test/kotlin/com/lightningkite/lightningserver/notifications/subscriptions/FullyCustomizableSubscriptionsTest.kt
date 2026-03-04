//package com.lightningkite.lightningserver.notifications.subscriptions
//
//import com.lightningkite.lightningserver.auth.require
//import com.lightningkite.lightningserver.definition.builder.ServerBuilder
//import com.lightningkite.lightningserver.notifications.*
//import com.lightningkite.lightningserver.notifications.events.Event
//import com.lightningkite.lightningserver.notifications.events.EventRegistry
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
//import kotlinx.serialization.json.Json
//import kotlin.test.Test
//import kotlin.test.assertEquals
//import kotlin.test.assertNotNull
//import kotlin.test.assertNull
//import kotlin.test.assertTrue
//import kotlin.uuid.Uuid
//
///**
// * Tests for [FullyCustomizableSubscriptions].
// * Verifies fully customizable subscriptions with user filters work correctly.
// */
//class FullyCustomizableSubscriptionsTest {
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
//        // Create shared registry that can be passed to both NotificationEndpoints and FullyCustomizableSubscriptions
//        val sharedRegistry = EventRegistry<TestUser>()
//
//        val notifications = path.path("notifications") module Notifications
//
//        object Notifications : NotificationEndpoints<
//            TestUser, Uuid,
//            TestNotificationContent,
//            NotificationBulkDispatcher<TestUser, Uuid, TestNotificationContent>,
//            FullyCustomizableSubscriptions<TestUser, Uuid>
//        >(
//            users = userInfo,
//            dispatcher = Dispatcher,
//            subscriptions = FullyCustomizableSubscriptions(
//                info = database.modelInfo(
//                    TestUser.require(),
//                    permissions = { ModelPermissions.allowAll() }
//                ),
//                users = userInfo,
//                principal = TestUser,
//                events = sharedRegistry
//            ),
//            registry = sharedRegistry
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
//            // Event that notifies based on filter conditions - uses Condition.Always
//            val modelCreated = event("model-created", modelInfo) { notif ->
//                notif.defaultSubscription(
//                    behavior = DefaultSubscriptionUpdateBehavior.UpdateReadPermissions
//                ) { user ->
//                    FullEventSubscription(
//                        filter = Condition.Always,
//                        email = Frequency.immediately(),
//                        sms = Frequency.immediately(),
//                        push = null,
//                        inApp = Frequency.immediately()
//                    )
//                }
//                notif.content { event ->
//                    { user -> TestNotificationContent.MODEL_CREATED }
//                }
//            }
//
//            // Event with filtered default subscription (only high value models)
//            val modelUpdated = event("model-updated", modelInfo) { notif ->
//                notif.defaultSubscription(
//                    behavior = DefaultSubscriptionUpdateBehavior.UpdateReadPermissions
//                ) { user ->
//                    FullEventSubscription(
//                        filter = condition<TestModel> { it.value gte 100 },
//                        email = Frequency.daily(9, 0),
//                        sms = null,
//                        push = Frequency.immediately(),
//                        inApp = Frequency.immediately()
//                    )
//                }
//                notif.content { event ->
//                    { user -> TestNotificationContent.MODEL_UPDATED }
//                }
//            }
//
//            // Event with ReplaceExistingWithDefault behavior
//            val modelDeleted = event("model-deleted", modelInfo) { notif ->
//                notif.defaultSubscription(
//                    behavior = DefaultSubscriptionUpdateBehavior.ReplaceExistingWithDefault
//                ) { user ->
//                    FullEventSubscription(
//                        filter = Condition.Always,
//                        email = Frequency.immediately(),
//                        sms = Frequency.immediately(),
//                        push = Frequency.immediately(),
//                        inApp = Frequency.immediately()
//                    )
//                }
//                notif.content { event ->
//                    { user -> TestNotificationContent.MODEL_DELETED }
//                }
//            }
//        }
//    }
//
//    // Helper to serialize conditions
//    private val json = Json { ignoreUnknownKeys = true }
//
//    // ===== Default Subscription Creation Tests =====
//
//    @Test
//    fun `default subscription created for new user`() {
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "NewUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                // Check that default subscription was created
//                val eventType = TestServer.Notifications.modelCreated.event.untyped
//                val subscriptionId = UserEventType(user._id, eventType)
//                val subscription = TestServer.Notifications.subscriptions.info.table()
//                    .get(subscriptionId)
//
//                assertNotNull(subscription)
//                assertEquals(user._id, subscription._id.user)
//                assertNotNull(subscription.email)
//            }
//        }
//    }
//
//    @Test
//    fun `user deletion removes subscriptions`() {
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "ToBeDeleted")
//                TestServer.userInfo.table().insertOne(user)
//
//                // Verify subscription was created
//                val eventType = TestServer.Notifications.modelCreated.event.untyped
//                val subscriptionId = UserEventType(user._id, eventType)
//                assertNotNull(TestServer.Notifications.subscriptions.info.table().get(subscriptionId))
//
//                // Delete user
//                TestServer.userInfo.table().deleteOneById(user._id)
//
//                // Subscription should be removed
//                assertNull(TestServer.Notifications.subscriptions.info.table().get(subscriptionId))
//            }
//        }
//    }
//
//    @Test
//    fun `default subscriptions created for all event types`() {
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "AllEventsUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                // Check that all three event types have subscriptions
//                val createdSub = TestServer.Notifications.subscriptions.info.table()
//                    .get(UserEventType(user._id, TestServer.Notifications.modelCreated.event.untyped))
//                val updatedSub = TestServer.Notifications.subscriptions.info.table()
//                    .get(UserEventType(user._id, TestServer.Notifications.modelUpdated.event.untyped))
//                val deletedSub = TestServer.Notifications.subscriptions.info.table()
//                    .get(UserEventType(user._id, TestServer.Notifications.modelDeleted.event.untyped))
//
//                assertNotNull(createdSub)
//                assertNotNull(updatedSub)
//                assertNotNull(deletedSub)
//            }
//        }
//    }
//
//    // ===== Filter Condition Tests =====
//
//    @Test
//    fun `filter condition filters events correctly`() {
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "FilterUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                // modelUpdated event has filter: value >= 100
//                val eventDef = TestServer.Notifications.modelUpdated.event
//
//                // Low value model - should not match filter
//                val lowValueModel = TestModel(value = 50, ownerId = user._id)
//                val lowValueEvent = Event(eventDef, lowValueModel)
//                val lowValueSubs = TestServer.Notifications.subscriptions.subscribed(lowValueEvent)
//                assertTrue(lowValueSubs.none { it.user == user._id })
//
//                // High value model - should match filter
//                val highValueModel = TestModel(value = 150, ownerId = user._id)
//                val highValueEvent = Event(eventDef, highValueModel)
//                val highValueSubs = TestServer.Notifications.subscriptions.subscribed(highValueEvent)
//                assertTrue(highValueSubs.any { it.user == user._id })
//            }
//        }
//    }
//
//    @Test
//    fun `modelCreated always filter receives all events`() {
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "AlwaysUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                // modelCreated has Condition.Always filter
//                val eventDef = TestServer.Notifications.modelCreated.event
//
//                // Any model should match
//                val model = TestModel(value = 0, ownerId = user._id)
//                val event = Event(eventDef, model)
//                val subs = TestServer.Notifications.subscriptions.subscribed(event)
//
//                assertTrue(subs.any { it.user == user._id })
//            }
//        }
//    }
//
//    // ===== User Preference Override Tests =====
//
//    @Test
//    fun `user can disable notification channel`() {
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "DisableChannelUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                val eventDef = TestServer.Notifications.modelCreated.event
//                val subscriptionId = UserEventType(user._id, eventDef.untyped)
//
//                // Get existing subscription and disable email
//                val existing = TestServer.Notifications.subscriptions.info.table().get(subscriptionId)
//                assertNotNull(existing)
//
//                TestServer.Notifications.subscriptions.info.table().replaceOneById(
//                    subscriptionId,
//                    existing.copy(email = null)
//                )
//
//                // Verify subscription now has null email
//                val updated = TestServer.Notifications.subscriptions.info.table().get(subscriptionId)
//                assertNotNull(updated)
//                assertNull(updated.email)
//            }
//        }
//    }
//
//    @Test
//    fun `user can change delivery frequency`() {
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "FrequencyChangeUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                val eventDef = TestServer.Notifications.modelCreated.event
//                val subscriptionId = UserEventType(user._id, eventDef.untyped)
//
//                // Get existing subscription and change email frequency
//                val existing = TestServer.Notifications.subscriptions.info.table().get(subscriptionId)
//                assertNotNull(existing)
//                assertEquals(Frequency.immediately(), existing.email) // Default
//
//                // Change to weekly
//                val weekly = Frequency.weekly(kotlinx.datetime.DayOfWeek.FRIDAY, 10, 0)
//                TestServer.Notifications.subscriptions.info.table().replaceOneById(
//                    subscriptionId,
//                    existing.copy(email = weekly)
//                )
//
//                // Verify change
//                val updated = TestServer.Notifications.subscriptions.info.table().get(subscriptionId)
//                assertNotNull(updated)
//                assertEquals(weekly, updated.email)
//            }
//        }
//    }
//
//    // ===== Multiple Users Tests =====
//
//    @Test
//    fun `multiple users receive independent subscriptions`() {
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user1 = TestUser(name = "User1")
//                val user2 = TestUser(name = "User2")
//                TestServer.userInfo.table().insertOne(user1)
//                TestServer.userInfo.table().insertOne(user2)
//
//                val eventType = TestServer.Notifications.modelCreated.event.untyped
//
//                // Both users should have independent subscriptions
//                val sub1 = TestServer.Notifications.subscriptions.info.table()
//                    .get(UserEventType(user1._id, eventType))
//                val sub2 = TestServer.Notifications.subscriptions.info.table()
//                    .get(UserEventType(user2._id, eventType))
//
//                assertNotNull(sub1)
//                assertNotNull(sub2)
//                assertEquals(user1._id, sub1._id.user)
//                assertEquals(user2._id, sub2._id.user)
//            }
//        }
//    }
//
//    @Test
//    fun `deleting one user does not affect others`() {
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user1 = TestUser(name = "User1ToDelete")
//                val user2 = TestUser(name = "User2ToKeep")
//                TestServer.userInfo.table().insertOne(user1)
//                TestServer.userInfo.table().insertOne(user2)
//
//                val eventType = TestServer.Notifications.modelCreated.event.untyped
//
//                // Delete user1
//                TestServer.userInfo.table().deleteOneById(user1._id)
//
//                // User1's subscription should be gone
//                assertNull(TestServer.Notifications.subscriptions.info.table()
//                    .get(UserEventType(user1._id, eventType)))
//
//                // User2's subscription should still exist
//                assertNotNull(TestServer.Notifications.subscriptions.info.table()
//                    .get(UserEventType(user2._id, eventType)))
//            }
//        }
//    }
//
//    // ===== Subscription Update Behavior Tests =====
//
//    @Test
//    fun `UpdateReadPermissions preserves user frequency changes`() {
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "PermissionsUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                val eventDef = TestServer.Notifications.modelCreated.event
//                val subscriptionId = UserEventType(user._id, eventDef.untyped)
//
//                // User customizes their email frequency
//                val existing = TestServer.Notifications.subscriptions.info.table().get(subscriptionId)
//                assertNotNull(existing)
//
//                val dailyFreq = Frequency.daily(10, 0)
//                TestServer.Notifications.subscriptions.info.table().replaceOneById(
//                    subscriptionId,
//                    existing.copy(email = dailyFreq)
//                )
//
//                // Update user (simulates permissions change trigger)
//                val updatedUser = user.copy(name = "UpdatedName")
//                TestServer.userInfo.table().replaceOneById(user._id, updatedUser)
//
//                // User's custom email frequency should be preserved
//                val afterUpdate = TestServer.Notifications.subscriptions.info.table().get(subscriptionId)
//                assertNotNull(afterUpdate)
//                assertEquals(dailyFreq, afterUpdate.email)
//            }
//        }
//    }
//
//    @Test
//    fun `ReplaceExistingWithDefault replaces user changes`() {
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "ReplaceUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                val eventDef = TestServer.Notifications.modelDeleted.event
//                val subscriptionId = UserEventType(user._id, eventDef.untyped)
//
//                // User customizes their subscription
//                val existing = TestServer.Notifications.subscriptions.info.table().get(subscriptionId)
//                assertNotNull(existing)
//
//                TestServer.Notifications.subscriptions.info.table().replaceOneById(
//                    subscriptionId,
//                    existing.copy(email = Frequency.weekly(kotlinx.datetime.DayOfWeek.MONDAY, 9, 0))
//                )
//
//                // Verify customization
//                val customized = TestServer.Notifications.subscriptions.info.table().get(subscriptionId)
//                assertEquals(Frequency.weekly(kotlinx.datetime.DayOfWeek.MONDAY, 9, 0), customized?.email)
//
//                // Update user (triggers ReplaceExistingWithDefault)
//                val updatedUser = user.copy(name = "UpdatedName")
//                TestServer.userInfo.table().replaceOneById(user._id, updatedUser)
//
//                // User's changes should be replaced with default
//                val afterUpdate = TestServer.Notifications.subscriptions.info.table().get(subscriptionId)
//                assertNotNull(afterUpdate)
//                // Default is Frequency.immediately()
//                assertEquals(Frequency.immediately(), afterUpdate.email)
//            }
//        }
//    }
//
//    // ===== Subscription Frequency Tests =====
//
//    @Test
//    fun `subscription frequencies are applied correctly`() {
//        TestServer.test({ context ->
//            sms setStatic TestSMS("sms", context)
//            email setStatic TestEmailService("email", context)
//        }) {
//            runBlocking {
//                val user = TestUser(name = "FrequencyUser")
//                TestServer.userInfo.table().insertOne(user)
//
//                val eventDef = TestServer.Notifications.modelUpdated.event
//                val model = TestModel(value = 150, ownerId = user._id)
//                val event = Event(eventDef, model)
//
//                val subs = TestServer.Notifications.subscriptions.subscribed(event)
//                val sub = subs.find { it.user == user._id }
//
//                assertNotNull(sub)
//                // modelUpdated configured with:
//                // email = Frequency.daily(9, 0)
//                // sms = null
//                // push = Frequency.immediately()
//                assertEquals(Frequency.daily(9, 0), sub.email)
//                assertNull(sub.sms)
//                assertEquals(Frequency.immediately(), sub.push)
//            }
//        }
//    }
//}
