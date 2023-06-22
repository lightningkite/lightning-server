package com.lightningkite.lightningserver.sms


class SMSException(override val message: String?) : Exception()

/**
 * An interface for sending SMS messages. This is used directly by the SMSSettings to abstract the implementation of
 * sending SMS messages away, so it can go to multiple places.
 */

interface SMSClient {
    suspend fun send(to: String, message: String)
}