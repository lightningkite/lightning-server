package com.lightningkite.ktorbatteries.notifications

import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MulticastMessage

object ConsoleNotificationInterface : NotificationInterface {
    override suspend fun send(
        targets: List<String>,
        title: String?,
        body: String?,
        imageUrl: String?,
        data: Map<String, String>?,
        critical: Boolean,
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

    override suspend fun send(message: Message) {
        if (System.getenv("test") == "true" && !NotificationSettings.instance.sendNotificationsDuringTests) return
        println(buildString {
            appendLine("-----NOTIFICATION-----")
            appendLine("Sending individual message.")
        })
    }

    override suspend fun send(message: MulticastMessage) {
        if (System.getenv("test") == "true" && !NotificationSettings.instance.sendNotificationsDuringTests) return
        println(buildString {
            appendLine("-----NOTIFICATION-----")
            appendLine("Sending Multicast message.")
        })
    }
}