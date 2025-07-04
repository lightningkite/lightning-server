package com.lightningkite.lightningserver.notifications

import com.lightningkite.EmailAddress
import com.lightningkite.PhoneNumber
import com.lightningkite.UUID
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.ModelPermissions
import com.lightningkite.lightningdb.condition
import com.lightningkite.lightningdb.eq
import com.lightningkite.lightningdb.findOne
import com.lightningkite.lightningdb.gte
import com.lightningkite.lightningdb.insertOne
import com.lightningkite.lightningdb.postCreate
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.modelInfo
import com.lightningkite.lightningserver.email.TestEmailClient
import com.lightningkite.lightningserver.events.EventHandler
import com.lightningkite.lightningserver.events.EventRegistry
import com.lightningkite.lightningserver.events.TypedEvent
import com.lightningkite.lightningserver.events.event
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.sms.TestSMSClient
import com.lightningkite.lightningserver.testmodels.TestThing
import com.lightningkite.lightningserver.testmodels.TestUser
import com.lightningkite.lightningserver.testmodels.value
import com.lightningkite.toEmailAddress
import com.lightningkite.toPhoneNumber
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.time.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NotificationTests {

    // Wrapping all the notification and event components into one place
    class NotificationEndpoints(path: ServerPath) : ServerPathGroup(path), EventHandler<TestUser> {
        val info = TestSettings.database.modelInfo<TestUser, NotificationForUser<UUID, NotificationContent.Basic>, UUID>(
            permissions = { ModelPermissions.allowAll() }
        )

        val dispatcher = object : NotificationDispatcher<TestUser, UUID, NotificationContent.Basic>(
            path,
            info,
            TestSettings.cache,
            TestSettings.database,
            TestSettings.userInfo,
            NotificationContent.Basic.serializer(),
            TestSettings.email,
            TestSettings.sms,
        ) {
            override suspend fun email(user: TestUser): EmailAddress? =
                try {
                    user.email.toEmailAddress()
                } catch (_: IllegalArgumentException) {
                    null
                }

            override suspend fun phone(user: TestUser): PhoneNumber? =
                try {
                    user.phoneNumber?.toPhoneNumber()
                } catch (_: IllegalArgumentException) {
                    null
                }

            override suspend fun fcmTokens(user: TestUser): Set<String> = emptySet()
            override suspend fun onFcmTokensDead(user: TestUser, deadTokens: Set<String>) {}
        }

        val subscriptions = PerUserSubscriptions(
            path("subscriptions"),
            TestSettings.database.modelInfo(
                permissions = { ModelPermissions.allowAll() }
            ),
            TestSettings.userInfo,
            TestSettings.events
        )

        val notifications = NotificationEventHandler(dispatcher, subscriptions, TestSettings.events)

        override val registry: EventRegistry<TestUser> get() = TestSettings.events
        override suspend fun <T : HasId<ID>, ID : Comparable<ID>> handle(event: TypedEvent<TestUser, T, ID>) = notifications.handle(event)
    }

    class OtherEndpoints(path: ServerPath) : ServerPathGroup(path) {
        val info = TestSettings.database.modelInfo<TestUser, TestThing, UUID>(
            permissions = { ModelPermissions.allowAll() },
        )
    }

    object TestServer : ServerPathGroup(TestSettings.path("server")) {
        val notifications = NotificationEndpoints(path("notifications"))
        val things = OtherEndpoints(path("test-things"))
    }

    init {
        TestServer
    }

    @Test
    fun defaultSubscriptionsAreInsertedAutomatically() = runBlocking {
        val testInfo = SubscriptionInfo<TestThing>(
            filter = condition { it.value gte 10 },
            email = NotificationFrequency.weekly(DayOfWeek.MONDAY, 12, 0),
            sms = NotificationFrequency.immediately(),
            push = null
        )

        val notif = TestServer.notifications.event("Test Thing Created", TestServer.things.info) { event ->
            subscriptions.setDefaultSubscription(event, DefaultSubscriptionBehavior.UpdateRetainingUserChanges) {
                testInfo
            }

            notifications.setContent(event) { thing ->
                {
                    NotificationContent.Basic(
                        title = "Thing Created",
                        body = "Thing was created: $thing"
                    )
                }
            }
        }

        val user = TestSettings.userInfo.collection().insertOne(
            TestUser(email = "test@email.com")
        )!!

        val sub = TestServer.notifications.subscriptions.info
            .collection()
            .findOne(condition { it._id.user eq user._id })

        assertEquals(sub?.requestedFilter, Serialization.json.encodeToString(testInfo.filter))
        assertEquals(sub?.email, testInfo.email)
        assertEquals(sub?.sms, testInfo.sms)
        assertEquals(sub?.push, testInfo.push)
    }

    @Test
    fun notificationsAreSentAutomatically() = runBlocking {
        val testInfo = SubscriptionInfo<TestThing>(
            email = NotificationFrequency.immediately(),
            sms = null,
            push = null
        )

        val notif = TestServer.notifications.event("Test Thing Created", TestServer.things.info) { event ->
            subscriptions.setDefaultSubscription(event, DefaultSubscriptionBehavior.UpdateRetainingUserChanges) {
                testInfo
            }

            notifications.setContent(event) { thing ->
                {
                    NotificationContent.Basic(
                        title = "Thing Created",
                        body = "Thing was created: $thing"
                    )
                }
            }
        }

        val user = TestSettings.userInfo.collection().insertOne(
            TestUser(email = "test@email.com")
        )

        val thing = TestServer.things.info.collection().insertOne(TestThing(value = 20))!!

        notif(thing)

        TestServer.notifications.dispatcher.refreshNotifications()

        delay(100)

        assertEquals(TestEmailClient.lastEmailSent?.subject, "Thing Created")

        Unit
    }

    @Test
    fun filterOnSubscriptionFiltersNotifications() = runBlocking {
        val testInfo = SubscriptionInfo<TestThing>(
            filter = condition { it.value gte 10 },
            email = NotificationFrequency.immediately(),
            sms = null,
            push = null
        )

        val notif = TestServer.notifications.event("Test Thing Created", TestServer.things.info) { event ->
            subscriptions.setDefaultSubscription(event, DefaultSubscriptionBehavior.UpdateRetainingUserChanges) {
                testInfo
            }

            notifications.setContent(event) { thing ->
                {
                    NotificationContent.Basic(
                        title = "Thing Created",
                        body = "Thing was created: $thing"
                    )
                }
            }
        }

        val user = TestSettings.userInfo.collection().insertOne(
            TestUser(email = "test@email.com")
        )

        var emailsSent = 0
        TestEmailClient.onEmailSent = {
            emailsSent++
        }

        val thing1 = TestServer.things.info.collection().insertOne(TestThing(value = 0))!!
        notif(thing1)

        TestServer.notifications.dispatcher.refreshNotifications()

        assertEquals(emailsSent, 0)

        val thing2 = TestServer.things.info.collection().insertOne(TestThing(value = 20))!!
        notif(thing2)

        TestServer.notifications.dispatcher.refreshNotifications()

        assertEquals(emailsSent, 1)
    }
}