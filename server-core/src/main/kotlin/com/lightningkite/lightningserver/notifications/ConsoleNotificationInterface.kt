package com.lightningkite.lightningserver.notifications

/**
 * The concrete implementation of NotificationInterface that will not send any notifications but just print them to the console.
 * This is useful for debugging and development.
 */
object ConsoleNotificationInterface : NotificationInterface {
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