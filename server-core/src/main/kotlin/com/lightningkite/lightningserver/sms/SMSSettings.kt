package com.lightningkite.lightningserver.sms

import com.lightningkite.lightningserver.settings.Pluggable
import kotlinx.serialization.Serializable

/**
 * SMSSettings defines where to send sms, and any credentials that may be required to do so.
 * There is only one built in live implementation so far through Twilio.
 *
 * @param url A string containing everything needed to connect to send as sms. The format is defined by the SMSClient that will consume it.
 *  For Twilio: twilio://[user]:[password]@[phoneNumber]
 *  For Console: console
 *  For Test: test
 */

@Serializable
data class SMSSettings(
    val url: String = "console",
    val from: String? = null,
) : () -> SMSClient {

    companion object : Pluggable<SMSSettings, SMSClient>() {
        init {
            SMSSettings.register("test") { TestSMSClient }
            SMSSettings.register("console") { ConsoleSMSClient }
            SMSSettings.register("twilio") {

                Regex("""twilio://(?<user>[^:]+):(?<password>[^@]+)(?:@(?<phoneNumber>.+))?""").matchEntire(it.url)
                    ?.let { match ->
                        TwilioSMSClient(
                            match.groups["user"]!!.value,
                            match.groups["password"]!!.value,
                            (it.from ?: match.groups["phoneNumber"]?.value
                            ?: throw IllegalStateException("Twilio Phone Number not provided."))
                        )
                    }
                    ?: throw IllegalStateException("Invalid Twilio Url. The URL should match the pattern: twilio://[user]:[password]@[phoneNumber]")
            }
        }
    }

    override fun invoke(): SMSClient = SMSSettings.parse(url.substringBefore("://"), this)

}