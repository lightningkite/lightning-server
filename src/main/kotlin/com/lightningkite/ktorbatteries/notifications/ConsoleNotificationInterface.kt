package com.lightningkite.ktorbatteries.notifications

object ConsoleNotificationInterface : NotificationInterface {
    override suspend fun send(targets: List<String>, title: String?, body: String?, imageUrl: String?) {
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
        })
    }
}