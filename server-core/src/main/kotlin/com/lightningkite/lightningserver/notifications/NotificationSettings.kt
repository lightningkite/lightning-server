package com.lightningkite.lightningserver.notifications

import com.lightningkite.lightningserver.settings.Pluggable
import kotlinx.serialization.Serializable


/**
 * NotificationSettings defines and configures how the server will send push notifications to clients.
 * Currently, the only built-in options Console and Tests.
 *
 * @param implementation can be "console" or "test"
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

    override fun invoke(): NotificationClient = parse(implementation.substringBefore("://").lowercase(), this)

}


