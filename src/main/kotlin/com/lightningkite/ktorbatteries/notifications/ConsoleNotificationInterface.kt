package com.lightningkite.ktorbatteries.notifications

object ConsoleNotificationInterface: NotificationInterface {
    override suspend fun send(targets: List<String>, title: String?, body: String?, imageUrl: String?) {
        if (targets.isEmpty()) return
        println(buildString {
            appendLine("-----NOTIFICATION-----")
            appendLine("To: ")
            for(target in targets) {
                appendLine(target)
            }
            appendLine("Title: $title")
            appendLine("Body: $body")
            appendLine("Image URL: $imageUrl")
        })
    }
}