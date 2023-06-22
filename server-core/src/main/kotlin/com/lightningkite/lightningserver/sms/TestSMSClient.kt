package com.lightningkite.lightningserver.sms

/**
 * A concrete implementation of SMSClient that will is similar to ConsoleSMSClient but with more options
 * You can turn off the console printing
 * It stores the last message sent
 * You can set a lambda for getting send events
 * This is useful for Unit Tests
 */

object TestSMSClient : SMSClient {
    data class Message(val to: String, val message: String)

    var printToConsole: Boolean = false
    var lastMessageSent: Message? = null
        private set
    var onMesasgeSent: ((Message)->Unit)? = null

    override suspend fun send(to: String, message: String) {
        val m = Message(to, message)
        lastMessageSent = m
        onMesasgeSent?.invoke(m)
        if (printToConsole)
            ConsoleSMSClient.send(to, message)
    }
}