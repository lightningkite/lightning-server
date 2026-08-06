package com.lightningkite.lightningserver.notifications

import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.require
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.notifications.SyntaxTest.User.Companion.testModelInfo
import com.lightningkite.lightningserver.notifications.events.event
import com.lightningkite.lightningserver.notifications.subscriptions.FrequencyCustomizableSubscriptions
import com.lightningkite.lightningserver.notifications.subscriptions.subscribed
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.settings.setStatic
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.lightningserver.typed.modelInfo
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import com.lightningkite.services.email.*
import com.lightningkite.services.notifications.NotificationData
import com.lightningkite.services.sms.SMS
import com.lightningkite.services.sms.TestSMS
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toSet
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.*
import kotlin.uuid.Uuid

class SyntaxTest {
    private object Server : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val cache = setting("cache", Cache.Settings())
        val sms = setting("sms", SMS.Settings())
        val email = setting("email", EmailService.Settings())

        val userInfo = database.modelInfo(
            User.require(),
            permissions = { ModelPermissions.allowAll<User>() },
        )

        val notifications = path.path("notifications") module Notifications

        val modelEndpoints = path.path("model") module ModelEndpoints
    }

    @Serializable
    data class User(override val _id: Uuid) : HasId<Uuid> {
        companion object : PrincipalType<User, Uuid> {
            override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
            override val subjectSerializer: KSerializer<User> = serializer()

            context(server: ServerRuntime)
            override suspend fun fetch(id: Uuid): User = User(id)

            context(builder: ServerBuilder)
            inline fun <reified T : HasId<ID>, reified ID : Comparable<ID>> Runtime<Database>.testModelInfo() =
                modelInfo(
                    User.require(),
                    permissions = { ModelPermissions.allowAll<T>() }
                )
        }
    }

    private object Notifications : ServerBuilder() {
        object Dispatcher : NotificationBulkDispatcher<User, Uuid, String>(
            info = Server.database.testModelInfo(),
            cache = Server.cache,
            database = Server.database,
            users = Server.userInfo,
            sms = Server.sms,
            email = Server.email,
            contentSerializer = String.serializer(),
        ) {
            context(server: ServerRuntime)
            override suspend fun email(user: User): EmailAddress = "fake@email.com".toEmailAddress()
            context(server: ServerRuntime)
            override suspend fun phone(user: User): PhoneNumber = "1234567890".toPhoneNumber()
            context(server: ServerRuntime)
            override suspend fun fcmTokens(user: User): Set<String> = emptySet()
            context(server: ServerRuntime)
            override suspend fun onFcmTokensDead(user: User, deadTokens: Set<String>) {
            }

            context(runtime: ServerRuntime)
            override suspend fun makeEmailNotifications(
                user: User,
                notifications: List<Notification<Uuid, String>>,
            ): List<Email> = notifications.map {
                Email(
                    subject = it.content,
                    to = listOf(EmailAddressWithName("test@email.com")),
                    plainText = ""
                )
            }

            context(runtime: ServerRuntime)
            override suspend fun makeSmsNotifications(
                user: User,
                notifications: List<Notification<Uuid, String>>,
            ): List<String> = notifications.map { it.content }

            context(runtime: ServerRuntime)
            override suspend fun makePushNotifications(
                user: User,
                notifications: List<Notification<Uuid, String>>,
            ): List<NotificationData> = emptyList()
        }

        val handler = path include NotificationEndpoints(
            Server.userInfo,
            Dispatcher,
            FrequencyCustomizableSubscriptions(
                info = Server.database.testModelInfo()
            )
        )
    }

    @Serializable
    @GenerateDataClassPaths
    data class Model(
        override val _id: Uuid = Uuid.random(),
        val name: String = "Hello World",
    ) : HasId<Uuid>


    private object ModelEndpoints : ServerBuilder() {
        val info: ModelInfo<User, Model, Uuid> = Server.database.modelInfo(
            auth = User.require(),
            permissions = { ModelPermissions.allowAll() },
            signals = { table ->
                table
                    .postCreate { somethingCreated(it) }
                    .postDelete { somethingDeleted(it) }
            }
        )

        val somethingCreated = Notifications.handler.event("Model Created", info) { notif ->
            notif.subscribed {
                users.table().all().map { it._id }.toSet()
            }
            notif.content { event ->
                { user ->
                    "Hello, $user. ${event.subject} was just created."
                }
            }
        }

        val somethingDeleted = Notifications.handler.event("Model Deleted", info) { notif ->
            notif.subscribed {
                users.table().all().map { it._id }.toSet()
            }
            notif.content { event ->
                { user ->
                    "Hello, $user. ${event.subject} was destroyed."
                }
            }
        }
    }

    object TestClock : Clock {
        var measuredFrom: Instant = Clock.System.now()
        var mark = TimeSource.Monotonic.markNow()

        override fun now(): Instant = measuredFrom + mark.elapsedNow()
    }

    @Test
    fun testCompiles() {
        var testSms: TestSMS? = null
        var testEmail: TestEmailService? = null

        Server.test(
            settings = { context ->
                sms setStatic TestSMS("sms", context).also { testSms = it }
                email setStatic TestEmailService("email", context).also { testEmail = it }
            },
            clock = { TestClock }
        ) {
            testSms!!
            testEmail!!

            runBlocking {
                Server.userInfo.table().insertOne(User(Uuid.random()))

                // Count all users - the subscriber notifies ALL users in the shared in-memory DB,
                // which may include users from other tests sharing this Server singleton.
                val userCount = Server.userInfo.table().count(Condition.Always)
                val smsCountBefore = testSms.messageHistory.size
                val emailCountBefore = testEmail.sentEmails.size

                modelEndpoints.info.table().insertOne(Model())

                assertEquals(userCount, testSms.messageHistory.size - smsCountBefore, "Failed at sms")
                assertEquals(userCount, testEmail.sentEmails.size - emailCountBefore, "Failed at email")
            }
        }
    }

    @Test
    fun testTimeTravel() {
        var testSms: TestSMS? = null
        var testEmail: TestEmailService? = null

        Server.test(
            settings = { context ->
                sms setStatic TestSMS("sms", context).also { testSms = it }
                email setStatic TestEmailService("email", context).also { testEmail = it }
            },
            clock = { TestClock }
        ) {
            testSms!!
            testEmail!!

            runBlocking {
                Server.userInfo.table().insertOne(User(Uuid.random()))

                val userCount = Server.userInfo.table().count(Condition.Always)
                val smsCountBefore = testSms.messageHistory.size
                val emailCountBefore = testEmail.sentEmails.size

                modelEndpoints.info.table().insertOne(Model())

                assertEquals(userCount, testSms.messageHistory.size - smsCountBefore, "Failed at sms")
                assertEquals(userCount, testEmail.sentEmails.size - emailCountBefore, "Failed at email")
            }
        }
    }
}