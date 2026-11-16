package com.lightningkite.lightningserver.notifications

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.notifications.events.event
import com.lightningkite.lightningserver.notifications.subscriptions.NonCustomizableSubscriptions
import com.lightningkite.lightningserver.notifications.subscriptions.subscribed
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.Database
import com.lightningkite.services.email.EmailService
import com.lightningkite.services.sms.SMS
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

/** Events are registered on the module that declares them, so the handler's checks must see the whole server. */
class RegistrationCheckTest {
    private class TestServer(content: Boolean, subscribers: Boolean) : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val cache = setting("cache", Cache.Settings())
        val userInfo = database.testModelInfo<TestUser, Uuid>()
        val modelInfo = database.testModelInfo<TestModel, Uuid>()

        val handler = path.path("notifications") include NotificationEndpoints(
            userInfo,
            object : TestDispatcherBase(
                info = database.testModelInfo(),
                cache = cache,
                database = database,
                users = userInfo,
                email = setting("email", EmailService.Settings()),
                sms = setting("sms", SMS.Settings()),
            ) {},
            NonCustomizableSubscriptions(),
        )

        val models = path.path("models") include object : ServerBuilder() {
            val created = this@TestServer.handler.event("Model Created", this@TestServer.modelInfo) { notif ->
                if (subscribers) notif.subscribed(Frequency.immediately(), null, null, null) { setOf(it.subject.ownerId) }
                if (content) notif.content { event -> { "Created: ${event.subject.name}" } }
            }
        }
    }

    @Test
    fun completeRegistrationBuilds() {
        TestServer(content = true, subscribers = true).build()
    }

    @Test
    fun missingContentFailsTheBuild() {
        val error = assertFailsWith<IllegalArgumentException> { TestServer(content = false, subscribers = true).build() }
        assertContains(error.message!!, "Content is missing")
    }

    @Test
    fun missingSubscribersFailsTheBuild() {
        val error = assertFailsWith<IllegalArgumentException> { TestServer(content = true, subscribers = false).build() }
        assertContains(error.message!!, "Subscriptions are missing")
    }
}
