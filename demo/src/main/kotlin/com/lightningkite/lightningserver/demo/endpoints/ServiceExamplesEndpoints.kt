package com.lightningkite.lightningserver.demo.endpoints

import com.lightningkite.lightningserver.auth.require
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.demo.Server
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.services.data.EmailAddress
import com.lightningkite.services.data.PhoneNumber
import com.lightningkite.services.email.Email
import com.lightningkite.services.email.EmailAddressWithName
import com.lightningkite.services.notifications.Notification
import com.lightningkite.services.notifications.NotificationData
import com.lightningkite.services.phonecall.OutboundCallOptions
import com.lightningkite.services.phonecall.TtsVoice

/**
 * ServiceExamplesEndpoints - Drives the outbound communication services that are declared as
 * settings on [Server] but otherwise never exercised by an endpoint: email, SMS, phone calls,
 * and push notifications (which had no setting at all until this file added one). Also reads
 * [Server.newSecret] to show what a "settings with instructions" value is for.
 *
 * These hit whatever real implementation settings.json currently names for each service - with
 * this demo's default settings.json, email and SMS log to the console, but phoneCall is
 * configured with a live Twilio account, so calling [placeCall] against the real server will
 * actually dial a real number. Auth-gated with UserAuth.require() accordingly.
 */
object ServiceExamplesEndpoints : ServerBuilder() {

    /**
     * POST /service-examples/email
     */
    val sendEmail = path.path("service-examples").path("email").post bind ApiHttpHandler(
        summary = "Send Demo Email",
        description = "Sends a plain-text message through the configured EmailService.",
        auth = Server.UserAuth.require(),
        implementation = { to: EmailAddress ->
            Server.email().send(
                Email(
                    subject = "Lightning Server Demo",
                    to = listOf(EmailAddressWithName(to)),
                    plainText = "This message came from the ServiceExamplesEndpoints demo endpoint.",
                )
            )
            "Sent to ${to.raw} via ${Server.email()::class.simpleName}."
        }
    )

    /**
     * POST /service-examples/sms
     */
    val sendSms = path.path("service-examples").path("sms").post bind ApiHttpHandler(
        summary = "Send Demo SMS",
        description = "Sends a message through the configured SMS service.",
        auth = Server.UserAuth.require(),
        implementation = { to: PhoneNumber ->
            Server.sms().send(to, "Lightning Server Demo message.")
            "Sent to ${to.raw} via ${Server.sms()::class.simpleName}."
        }
    )

    /**
     * POST /service-examples/phone-call
     *
     * Starts a call, speaks a line, then hangs up.
     */
    val placeCall = path.path("service-examples").path("phone-call").post bind ApiHttpHandler(
        summary = "Place Demo Phone Call",
        description = "Starts a call, speaks a line, then hangs up.",
        auth = Server.UserAuth.require(),
        implementation = { to: PhoneNumber ->
            val phone = Server.phoneCall()
            val callId = phone.startCall(to, OutboundCallOptions())
            phone.speak(callId, "This is the Lightning Server demo calling.", TtsVoice())
            phone.hangup(callId)
            "Call $callId placed to ${to.raw} via ${phone::class.simpleName}."
        }
    )

    /**
     * POST /service-examples/notify
     *
     * Pushes a notification to the given device token. There's no device-token-registration
     * model in this demo, so the token is taken directly as input rather than looked up.
     */
    val pushNotification = path.path("service-examples").path("notify").post bind ApiHttpHandler(
        summary = "Push Demo Notification",
        description = "Sends a push notification to the given device token via the configured NotificationService.",
        auth = Server.UserAuth.require(),
        implementation = { deviceToken: String ->
            val results = Server.notifications().send(
                listOf(deviceToken),
                NotificationData(notification = Notification(title = "Lightning Server", body = "Demo notification."))
            )
            "Sent via ${Server.notifications()::class.simpleName}: $results"
        }
    )

    /**
     * GET /service-examples/secret
     *
     * `someSecret` carries no meaning of its own - it exists purely to demonstrate the
     * `instructions` parameter on [ServerBuilder.setting], which surfaces guidance for whoever
     * fills in settings.json (see the generated settings schema).
     */
    val readSecret = path.path("service-examples").path("secret").get bind ApiHttpHandler(
        summary = "Read Demo Secret",
        description = "Returns the current value of the someSecret setting.",
        auth = Server.UserAuth.require(),
        implementation = { _: Unit ->
            "someSecret is currently: ${Server.newSecret()}"
        }
    )
}
