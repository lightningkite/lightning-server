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
        if (targets.isEmpty() || (System.getenv("test") == "true" && !NotificationSettings.instance.sendNotificationsDuringTests)) return
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

}