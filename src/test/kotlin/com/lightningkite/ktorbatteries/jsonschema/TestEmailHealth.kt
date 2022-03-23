package com.lightningkite.ktorbatteries.jsonschema

import com.lightningkite.ktorbatteries.email.EmailClientOption
import com.lightningkite.ktorbatteries.email.EmailSettings
import com.lightningkite.ktorbatteries.email.SmtpConfig
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TestEmailHealth {

    @Test
    fun testhealth(): Unit = runBlocking {
        EmailSettings(
            EmailClientOption.Smtp,
            SmtpConfig(
                "smtp.gmail.com",
                465,
                "brady@lightningkite.com",
                "",
                true,
                "brady@lightningkite.com",
            )
        )

        println(EmailSettings.instance.healthCheck2())
    }

}