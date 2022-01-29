package com.lightningkite.ktorbatteries.notifications

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.lightningkite.ktorbatteries.SettingSingleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.io.File

@Serializable
data class NotificationSettings(
    val implementation: NotificationImplementation = NotificationImplementation.Console,
    val credentials: String? = null
) {
    companion object : SettingSingleton<NotificationSettings>()

    init {
        instance = this
    }

    @Transient
    var sendNotificationsDuringTests: Boolean = false

    val notifications by lazy {
        when (implementation) {
            NotificationImplementation.Console -> ConsoleNotificationInterface
            NotificationImplementation.FCM -> {
                assert(credentials != null) { "FCM was selected for notification implementation, but no credential file was provided." }
                val file = File(credentials!!)
                assert(file.exists()) { "FCM credentials file not found at '$file'" }
                FirebaseApp.initializeApp(
                    FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(file.inputStream()))
                        .build()
                )
                FcmNotificationInterface
            }
        }
    }
}

val notifications: NotificationInterface get() = NotificationSettings.instance.notifications

enum class NotificationImplementation {
    Console, FCM
}

