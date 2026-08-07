package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.demo.endpoints.ServiceExamplesEndpoints
import com.lightningkite.lightningserver.runtime.test.TestRunner
import com.lightningkite.lightningserver.runtime.test.testBlocking
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.data.toEmailAddress
import com.lightningkite.services.data.toPhoneNumber
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.insertOne
import com.lightningkite.services.email.EmailService
import com.lightningkite.services.email.TestEmailService
import com.lightningkite.services.notifications.NotificationService
import com.lightningkite.services.notifications.TestNotificationService
import com.lightningkite.services.phonecall.CallStatus
import com.lightningkite.services.phonecall.PhoneCallService
import com.lightningkite.services.phonecall.TestPhoneCallService
import com.lightningkite.services.sms.SMS
import com.lightningkite.services.sms.TestSMS
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises ServiceExamplesEndpoints against Test* service implementations - never against real
 * providers - so these run safely in CI without sending real messages or dialing real numbers.
 */
class ServiceExamplesEndpointsTest {

    /** Overrides every outbound service with its in-memory Test implementation. */
    private fun serviceTest(action: suspend context(TestRunner<Server>) Server.() -> Unit) =
        Server.testBlocking(
            settings = {
                database set Database.Settings("ram")
                email set EmailService.Settings("test")
                sms set SMS.Settings("test")
                phoneCall set PhoneCallService.Settings("test")
                notifications set NotificationService.Settings("test")
            },
            action = action,
        )

    @Test
    fun sendEmailUsesTheConfiguredEmailService() = serviceTest {
        val user = Server.userInfo.table().insertOne(User(email = "caller@example.com"))!!
        val auth = Authentication(Server.UserAuth, id = user._id, sessionId = null)

        ServiceExamplesEndpoints.sendEmail.test(auth, "recipient@example.com".toEmailAddress())

        val sent = (Server.email() as TestEmailService).lastEmailTo("recipient@example.com")
        assertEquals("Lightning Server Demo", sent?.subject)
    }

    @Test
    fun sendSmsUsesTheConfiguredSmsService() = serviceTest {
        val user = Server.userInfo.table().insertOne(User(email = "caller@example.com"))!!
        val auth = Authentication(Server.UserAuth, id = user._id, sessionId = null)

        ServiceExamplesEndpoints.sendSms.test(auth, "+15555550123".toPhoneNumber())

        val sms = Server.sms() as TestSMS
        assertEquals("+15555550123".toPhoneNumber(), sms.lastMessageSent?.to)
    }

    @Test
    fun placeCallStartsSpeaksAndHangsUp() = serviceTest {
        val user = Server.userInfo.table().insertOne(User(email = "caller@example.com"))!!
        val auth = Authentication(Server.UserAuth, id = user._id, sessionId = null)

        ServiceExamplesEndpoints.placeCall.test(auth, "+15555550123".toPhoneNumber())

        val phone = Server.phoneCall() as TestPhoneCallService
        assertEquals(1, phone.calls.size)
        val call = phone.calls.values.single()
        assertEquals(CallStatus.COMPLETED, call.status)
        assertTrue(phone.spokenMessages.any { it.callId == call.callId })
    }

    @Test
    fun pushNotificationSendsToTheGivenToken() = serviceTest {
        val user = Server.userInfo.table().insertOne(User(email = "caller@example.com"))!!
        val auth = Authentication(Server.UserAuth, id = user._id, sessionId = null)

        ServiceExamplesEndpoints.pushNotification.test(auth, "device-token-123")

        val notifications = Server.notifications() as TestNotificationService
        assertEquals(listOf("device-token-123"), notifications.lastMessageSent?.targets)
    }

    @Test
    fun readSecretReturnsTheConfiguredValue() = serviceTest {
        val user = Server.userInfo.table().insertOne(User(email = "caller@example.com"))!!
        val auth = Authentication(Server.UserAuth, id = user._id, sessionId = null)

        val result = ServiceExamplesEndpoints.readSecret.test(auth, Unit)

        assertTrue(result.contains("???"), "default someSecret value should come through: $result")
    }
}
