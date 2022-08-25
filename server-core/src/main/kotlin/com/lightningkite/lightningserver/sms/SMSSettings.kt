package com.lightningkite.lightningserver.sms

import com.lightningkite.lightningserver.settings.Pluggable
import kotlinx.serialization.Serializable

@Serializable
data class SMSSettings(
    val url: String = "console",
    val from:String? = null,
) : () -> SMSClient {

    companion object : Pluggable<SMSSettings, SMSClient>() {
        init {
            SMSSettings.register("console") { ConsoleSMSClient }
            SMSSettings.register("twilio") {
                val urlWithoutProtocol = it.url.substringAfter("://")
                val auth = urlWithoutProtocol.substringBefore("@")
                val from = urlWithoutProtocol.substringAfter("@")
                TwilioSMSClient(
                    auth.substringBefore(':'),
                    auth.substringAfter(':'),
                    it.from ?: from
                )
            }
        }
    }

    override fun invoke(): SMSClient = SMSSettings.parse(url.substringBefore("://"), this)

}