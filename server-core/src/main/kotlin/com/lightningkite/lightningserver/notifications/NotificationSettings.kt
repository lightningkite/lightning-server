package com.lightningkite.lightningserver.notifications

import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import com.lightningkite.lightningserver.settings.Pluggable
import com.lightningkite.lightningserver.settings.setting
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.io.File


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
): ()->NotificationInterface {

    companion object: Pluggable<NotificationSettings, NotificationInterface>() {
        init {
            register("console") { ConsoleNotificationInterface }
        }
    }

    override fun invoke(): NotificationInterface = parse(implementation.lowercase(), this)

}


