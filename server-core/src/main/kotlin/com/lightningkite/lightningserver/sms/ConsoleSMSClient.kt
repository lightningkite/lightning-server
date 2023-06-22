package com.lightningkite.lightningserver.sms

object ConsoleSMSClient : SMSClient {
    override suspend fun send(to: String, message: String) {
        println("SMS to $to:")
        println(message)
        println()
    }
}