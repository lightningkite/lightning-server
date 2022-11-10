package com.lightningkite.lightningserver.sms

object ConsoleSMSClient : SMSClient {
    var lastMessageSent: Message? = null
    data class Message(val to: String, val message: String)
    override suspend fun send(to: String, message: String) {
        println("SMS to $to:")
        println(message)
        println()
        lastMessageSent = Message(to, message)
    }
}