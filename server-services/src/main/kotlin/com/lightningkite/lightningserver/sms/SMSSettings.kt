package com.lightningkite.lightningserver.sms

import com.lightningkite.lightningserver.exceptions.ExceptionReporter
import com.lightningkite.lightningserver.exceptions.ExceptionSettings
import com.lightningkite.lightningserver.services.Pluggable
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
) : SMSClient {

    companion object : Pluggable<SMSSettings, SMSClient>() {
        init {
            SMSSettings.register("test") { TestSMSClient }
            SMSSettings.register("console") { ConsoleSMSClient }
        }
    }


    private var backing: SMSClient? = null
    val wraps: SMSClient
        get() {
            if(backing == null) backing = parse(url.substringBefore("://"), this)
            return backing!!
        }

    override suspend fun send(to: String, message: String) = wraps.send(to, message)
}