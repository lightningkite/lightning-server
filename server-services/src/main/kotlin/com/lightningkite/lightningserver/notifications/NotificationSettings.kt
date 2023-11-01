package com.lightningkite.lightningserver.notifications

import com.lightningkite.lightningserver.metrics.MetricSettings
import com.lightningkite.lightningserver.metrics.Metrics
import com.lightningkite.lightningserver.services.Pluggable
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
) : NotificationClient {

    companion object : Pluggable<NotificationSettings, NotificationClient>() {
        init {
            register("test") { TestNotificationClient }
            register("console") { ConsoleNotificationClient }
        }
    }

    private var backing: NotificationClient? = null
    val wraps: NotificationClient
        get() {
            if(backing == null) backing = parse(implementation, this)
            return backing!!
        }

    override suspend fun send(targets: List<String>, data: NotificationData) = wraps.send(targets, data)
}


