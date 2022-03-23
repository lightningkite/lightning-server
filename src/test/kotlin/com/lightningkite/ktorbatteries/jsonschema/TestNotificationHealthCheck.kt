package com.lightningkite.ktorbatteries.jsonschema

import com.lightningkite.ktorbatteries.notifications.NotificationImplementation
import com.lightningkite.ktorbatteries.notifications.NotificationSettings
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TestNotificationHealthCheck {

    @Test
    fun testHealth(): Unit = runBlocking {
        NotificationSettings(
            NotificationImplementation.FCM,
            "C:/Users/Brady Svedin/Lightning Kite/Related Docs/E3/google_creds.json"
        )

        println(NotificationSettings.instance.healthCheck())
    }

}