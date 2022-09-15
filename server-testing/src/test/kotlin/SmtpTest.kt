package com.lightningkite.lightningdb.test

import com.lightningkite.lightningserver.email.EmailSettings
import com.lightningkite.lightningserver.email.SmtpConfig
import com.lightningkite.lightningserver.email.SmtpEmailClient
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SmtpTest {
    @Test fun test() {
//        val smtp = EmailSettings("smtp://AKIA5GXOGORF4BIVQC4A:BG15Qf2o4zcBhifzMCIMoICvGWCAqYhqnUIV2RTzzZsx@email-smtp.us-west-2.amazonaws.com:587", fromEmail = "joseph@lightningkite.com")()
        val smtp = SmtpEmailClient(
            SmtpConfig(
                hostName = "email-smtp.us-west-2.amazonaws.com",
                port = 587,
                username = "AKIA5GXOGORF4BIVQC4A",
                password = "BG15Qf2o4zcBhifzMCIMoICvGWCAqYhqnUIV2RTzzZsx",
                fromEmail = "joseph@lightningkite.com"
        ))
        runBlocking {
            smtp.send(
                subject = "Test",
                to = listOf("joseph@lightningkite.com"),
                message = "Test Message"
            )
        }
    }
}