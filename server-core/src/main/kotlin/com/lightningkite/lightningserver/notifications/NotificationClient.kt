package com.lightningkite.lightningserver.notifications

import kotlin.time.Duration


@Deprecated("Use the new name", ReplaceWith("NotificationPriority", "com.lightningkite.lightningserver.notifications"))
typealias Priority = NotificationPriority

enum class NotificationPriority {
    HIGH,
    NORMAL
}

@Deprecated("Use the new name", ReplaceWith("NotificationAndroid", "com.lightningkite.lightningserver.notifications"))
typealias Android = NotificationAndroid

data class NotificationAndroid(
    val channel: String? = null,
    val priority: NotificationPriority = NotificationPriority.NORMAL,
    val sound: String? = null,
)

data class Notification(
    val title: String? = null,
    val body: String? = null,
    val imageUrl: String? = null,
    val link: String? = null,
)

@Deprecated("Use the new name", ReplaceWith("NotificationIos", "com.lightningkite.lightningserver.notifications"))
typealias iOS = NotificationIos

data class NotificationIos(
    val critical: Boolean = false,
    val sound: String? = null
)

@Deprecated("Use the new name", ReplaceWith("NotificationWeb", "com.lightningkite.lightningserver.notifications"))
typealias Web = NotificationWeb

data class NotificationWeb(
    val data: Map<String, String> = mapOf(),
)

data class NotificationData(
    val notification: Notification? = null,
    val data: Map<String, String>? = null,
    val android: NotificationAndroid? = null,
    val ios: NotificationIos? = null,
    val web: NotificationWeb? = null,
    val timeToLive: Duration? = null
)


interface NotificationClient {
    suspend fun send(
        targets: List<String>,
        title: String? = null,
        body: String? = null,
        imageUrl: String? = null,
        data: Map<String, String>? = null,
        critical: Boolean = false,
        androidChannel: String? = null,
        timeToLive: Duration? = null,
        link: String? = null
    ) = send(
        targets = targets,
        data = NotificationData(
            notification = Notification(title, body, imageUrl, link = link),
            data = data,
            android = androidChannel?.let {
                NotificationAndroid(
                    it,
                    priority = if (critical) NotificationPriority.HIGH else NotificationPriority.NORMAL
                )
            },
            ios = NotificationIos(critical = critical, sound = "default"),
            timeToLive = timeToLive
        )
    )

    suspend fun send(
        targets: List<String>,
        notification: Notification? = null,
        data: Map<String, String>? = null,
        android: NotificationAndroid? = null,
        ios: NotificationIos? = null,
        web: NotificationWeb? = null,
    ) = send(targets, NotificationData(notification, data, android, ios, web))

    /**
     * @return Targets which successfully sent
     */
    suspend fun send(targets: List<String>, data: NotificationData): Map<String, NotificationSendResult>
}

enum class NotificationSendResult {
    DeadToken, Failure, Success
}