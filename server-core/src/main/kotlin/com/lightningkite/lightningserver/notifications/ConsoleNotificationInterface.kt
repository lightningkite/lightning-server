package com.lightningkite.lightningserver.notifications

/**
 * The concrete implementation of NotificationInterface that will not send any notifications but just print them to the console.
 * This is useful for debugging and development.
 */
object ConsoleNotificationInterface : NotificationInterface {
    override suspend fun send(
        targets: List<String>,
        title: String?,
        body: String?,
        imageUrl: String?,
        data: Map<String, String>?,
        critical: Boolean,
        androidChannel: String?,
    ) {
        println(buildString {
            appendLine("-----NOTIFICATION-----")
            appendLine("To: ")
            for (target in targets) {
                appendLine(target)
            }
            appendLine("Title: $title")
            appendLine("Body: $body")
            appendLine("Image URL: $imageUrl")
            appendLine("Critical: $critical")
            if (data?.isNotEmpty() == true) appendLine("Data: {${data.entries.joinToString { "${it.key}: ${it.value} " }}}")
        })
    }

    override suspend fun send(
        targets: List<String>,
        notification: Notification?,
        data: Map<String, String>?,
        android: Android?,
        ios: iOS?,
        web: Web?
    ) {

        println(buildString {
            appendLine("-----NOTIFICATION-----")
            appendLine("To: ")
            for (target in targets) {
                appendLine(target)
            }
            if(notification != null) {
                appendLine("Title: ${notification.title}")
                appendLine("Body: ${notification.body}")
                appendLine("Image URL: ${notification.imageUrl}")
            }
            if (data?.isNotEmpty() == true) appendLine("Data: {${data.entries.joinToString { "${it.key}: ${it.value} " }}}")
        })
    }

}