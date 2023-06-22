package com.lightningkite.lightningserver.notifications

/**
 * The concrete implementation of NotificationClient that will not send any notifications to an external source but just potentially print them to the console,
 * Store the last message, and call a event handler each time a message is sent.
 * This is useful for unit tests.
 */
object TestNotificationClient : NotificationClient {
    data class Message(val targets: List<String>, val data: NotificationData)

    var printToConsole: Boolean = false
    var lastMessageSent: Message? = null
        private set
    var onMesasgeSent: ((Message)->Unit)? = null

    override suspend fun send(
        targets: List<String>,
        data: NotificationData
    ) {
        val m = Message(targets, data)
        lastMessageSent = m
        onMesasgeSent?.invoke(m)
        if(printToConsole)
            ConsoleNotificationClient.send(targets, data)
    }

}