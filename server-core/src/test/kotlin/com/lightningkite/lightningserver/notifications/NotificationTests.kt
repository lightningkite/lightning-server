package com.lightningkite.lightningserver.notifications

import com.lightningkite.EmailAddress
import com.lightningkite.PhoneNumber
import com.lightningkite.UUID
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.ModelPermissions
import com.lightningkite.lightningdb.condition
import com.lightningkite.lightningdb.gte
import com.lightningkite.lightningdb.postCreate
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.modelInfo
import com.lightningkite.lightningserver.events.EventHandler
import com.lightningkite.lightningserver.events.EventRegistry
import com.lightningkite.lightningserver.events.TypedEvent
import com.lightningkite.lightningserver.events.event
import com.lightningkite.lightningserver.testmodels.TestThing
import com.lightningkite.lightningserver.testmodels.TestUser
import com.lightningkite.lightningserver.testmodels.value
import com.lightningkite.toEmailAddress
import com.lightningkite.toPhoneNumber
import kotlin.test.Test

class NotificationTests {

    // Wrapping all the notification and event components into one place
    class NotificationEndpoints(path: ServerPath) : ServerPathGroup(path), EventHandler<TestUser> {
        val info = TestSettings.database.modelInfo<TestUser, NotificationForUser<UUID, NotificationContent.Basic>, UUID>(
            permissions = { ModelPermissions.allowAll() }
        )

        // The scheduler
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
            signals = { it.postCreate(::postCreate) }
        )

        suspend fun postCreate(thing: TestThing) { thingCreatedNotif(thing) }

        val thingCreatedNotif = TestServer.notifications.event("Test Thing Created", info) { event ->
            subscriptions.setDefaultSubscription(event, DefaultSubscriptionBehavior.UpdateRetainingUserChanges) { user ->
                SubscriptionInfo(
                    filter = condition { it.value gte 10 }
                )
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
    }

    object TestServer : ServerPathGroup(TestSettings.path("server")) {
        val notifications = NotificationEndpoints(path("notifications"))
        val things = OtherEndpoints(path("test-things"))
    }

    init {
        TestServer
    }

    @Test
    fun notificationsAreSentAutomatically() {

    }
}