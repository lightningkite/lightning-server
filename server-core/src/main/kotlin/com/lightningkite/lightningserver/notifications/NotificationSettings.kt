package com.lightningkite.lightningserver.notifications

import com.lightningkite.lightningserver.settings.Pluggable
import kotlinx.serialization.Serializable


/**
 * NotificationSettings defines and configures how the server will send push notifications to clients.
 * Currently, the only supported ways are Console and FCM/Firebase Messaging.
 * Console will print all notifications straight to the console rather than send them to the client.
 * FCM will use Firebase Messaging to send push notification so clients.
 *
 * @param implementation can be "Console" or "FCM"
 * @param credentials is an api key for communicating with an external service.
 */
@Serializable
data class NotificationSettings(
    val implementation: String = "console",
    val credentials: String? = null
) : () -> NotificationClient {

    companion object : Pluggable<NotificationSettings, NotificationClient>() {
        init {
            register("test") { TestNotificationClient }
            register("console") { ConsoleNotificationClient }
        }
    }

    override fun invoke(): NotificationClient = parse(implementation.lowercase(), this)

}


