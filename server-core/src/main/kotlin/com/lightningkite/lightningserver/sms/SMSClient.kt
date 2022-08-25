package com.lightningkite.lightningserver.sms


class SMSException(override val message: String?) : Exception()


interface SMSClient {
    suspend fun send(to: String, message: String)
}