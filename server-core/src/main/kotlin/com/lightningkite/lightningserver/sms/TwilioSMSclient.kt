package com.lightningkite.lightningserver.sms

import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.logger
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*

/**
 * An SMSClient implementation that sends real texts using the Twilio API.
 */

class TwilioSMSClient(
    val account: String,
    val key: String,
    val from: String,
) : SMSClient {
    override suspend fun send(to: String, message: String) {
        with(
            client.submitForm(
                url = "https://api.twilio.com/2010-04-01/Accounts/${account}/Messages.json",
                formParameters = Parameters.build {
                    append("From", from)
                    append("To", to)
                    append("Body", message)
                },
            ) {
                basicAuth(account, key)
            }) {
            if (status != HttpStatusCode.Created) {
                val result = bodyAsText()
                logger.error(result)
                throw SMSException(result)
            }
        }
    }
}