package com.lightningkite.lightningserver.notifications

/**
 * The concrete implementation of NotificationClient that will is similar to ConsoleNotificationClient but with more options
 * You can turn off the console printing
 * It stores the last message sent
 * You can set a lambda for getting send events
 * This is useful for Unit Tests
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
    ): Map<String, NotificationSendResult> {
        val m = Message(targets, data)
        lastMessageSent = m
        onMesasgeSent?.invoke(m)
        if(printToConsole)
            ConsoleNotificationClient.send(targets, data)
        return targets.associateWith { NotificationSendResult.Success }
    }

}