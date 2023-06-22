package com.lightningkite.lightningserver.notifications

/**
 * The concrete implementation of NotificationClient that will simply print out everything to the console
 * This is useful for local development.
 */
object ConsoleNotificationClient : NotificationClient {
    override suspend fun send(
        targets: List<String>,
        data: NotificationData
    ) {

        println(buildString {
            appendLine("-----NOTIFICATION-----")
            appendLine("To: ")
            for (target in targets) {
                appendLine(target)
            }
            data.notification?.let { notification ->
                appendLine("Title: ${notification.title}")
                appendLine("Body: ${notification.body}")
                appendLine("Image URL: ${notification.imageUrl}")
            }
            if (data.data?.isNotEmpty() == true)
                appendLine("Data: {${data.data.entries.joinToString { "${it.key}: ${it.value} " }}}")
        })
    }

}