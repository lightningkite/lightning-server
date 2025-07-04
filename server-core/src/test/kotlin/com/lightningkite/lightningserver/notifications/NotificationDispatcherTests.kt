package com.lightningkite.lightningserver.notifications

import com.lightningkite.EmailAddress
import com.lightningkite.PhoneNumber
import com.lightningkite.UUID
import com.lightningkite.lightningdb.ModelPermissions
import com.lightningkite.lightningdb.get
import com.lightningkite.lightningdb.insertOne
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.db.modelInfo
import com.lightningkite.lightningserver.email.Email
import com.lightningkite.lightningserver.email.EmailLabeledValue
import com.lightningkite.lightningserver.email.TestEmailClient
import com.lightningkite.lightningserver.sms.TestSMSClient
import com.lightningkite.lightningserver.testmodels.TestUser
import com.lightningkite.now
import com.lightningkite.toEmailAddress
import com.lightningkite.toPhoneNumber
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationDispatcherTests {
    private val testFcmTokens = setOf("12345")

    private var bulkNotifications = false

    val dispatcher = object : NotificationDispatcher<TestUser, UUID, NotificationContent.Basic>(
        TestSettings.path("notifications"),
        TestSettings.database.modelInfo(
            permissions = { ModelPermissions.allowAll() }
        ),
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

        override suspend fun fcmTokens(user: TestUser): Set<String> = testFcmTokens
        override suspend fun onFcmTokensDead(user: TestUser, deadTokens: Set<String>) {}

        override suspend fun makeEmailNotifications(user: TestUser, notifications: List<NotificationForUser<UUID, NotificationContent.Basic>>): List<Email> =
            if (bulkNotifications) listOf(
                Email(
                    "Bulked Email",
                    to = listOf(EmailLabeledValue(user.email)),
                    plainText = notifications.joinToString { it.content.body }
                )
            )
            else super.makeEmailNotifications(user, notifications)

        override suspend fun makeSmsNotifications(user: TestUser, notifications: List<NotificationForUser<UUID, NotificationContent.Basic>>): List<String> =
            if (bulkNotifications) listOf(
                "Bulked SMS\n" + notifications.joinToString(separator = "\n") { it.content.body }
            )
            else super.makeSmsNotifications(user, notifications)

        override suspend fun makePushNotifications(user: TestUser, notifications: List<NotificationForUser<UUID, NotificationContent.Basic>>): List<NotificationData> =
            if (bulkNotifications) listOf(
                NotificationData(
                    notification = Notification(
                        title = "Bulked Push",
                        body = notifications.joinToString(separator = "\n\n") { it.content.body },
                        link = null
                    ),
                    data = emptyMap(),
                    android = NotificationAndroid(),
                    ios = NotificationIos(),
                    web = NotificationWeb()
                )
            )
            else super.makePushNotifications(user, notifications)
    }

    private var emailsSent = 0
    private var smsSent = 0
    private var pushSent = 0

    init {
        TestEmailClient.onEmailSent = { emailsSent++ }
        TestSMSClient.onMesasgeSent = { smsSent++ }
        TestNotificationClient.onMesasgeSent = { pushSent++ }
    }

    @BeforeTest
    fun clearSends() {
        emailsSent = 0
        smsSent = 0
        pushSent = 0
    }

    val userId = UUID.random()

    private suspend fun user() = TestSettings.userInfo.collection().get(userId)!!

    @BeforeTest
    fun insertUser() {
        runBlocking { TestSettings.userInfo.collection().insertOne(TestUser(userId, "test@email.com")) }
    }

    private fun notification(
        title: String,
        body: String = "Test Notification",
        email: NotificationFrequency? = NotificationFrequency.immediately(),
        sms: NotificationFrequency? = NotificationFrequency.immediately(),
        push: NotificationFrequency? = NotificationFrequency.immediately(),
    ): NotificationForUser<UUID, NotificationContent.Basic> {
        val now = now()

        return NotificationForUser(
            event = Event(type = EventType("Test Event Type"), subject = ""),
            user = userId,
            content = NotificationContent(title, body),
            email = email?.sendAt(now)?.let(::SendInfo),
            sms = sms?.sendAt(now)?.let(::SendInfo),
            push = push?.sendAt(now)?.let(::SendInfo)
        )
    }

    @Test
    fun dispatchesToMethodClients() = runBlocking {
        assertEquals(0, emailsSent, "email")
        assertEquals(0, smsSent, "sms")
        assertEquals(0, pushSent, "push")

        dispatcher.info.collection().insertOne(notification("First"))

        dispatcher.refreshNotifications()

        assertEquals(1, emailsSent, "email")
        assertEquals(1, smsSent, "sms")
        assertEquals(1, pushSent, "push")

        dispatcher.info.collection().insertOne(notification("Second"))

        dispatcher.refreshNotifications()

        assertEquals(2, emailsSent, "email")
        assertEquals(2, smsSent, "sms")
        assertEquals(2, pushSent, "push")

        Unit
    }
}