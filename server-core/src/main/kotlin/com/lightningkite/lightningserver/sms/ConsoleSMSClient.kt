package com.lightningkite.lightningserver.sms

/**
 * A concrete implementation of SMSClient that will simply print out everything to the console
 * This is useful for local development
 */

object ConsoleSMSClient : SMSClient {
    override suspend fun send(to: String, message: String) {
        println("SMS to $to:")
        println(message)
        println()
    }
}