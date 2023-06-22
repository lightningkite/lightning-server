package com.lightningkite.lightningserver.sms

import com.lightningkite.lightningserver.email.TestEmailClient

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