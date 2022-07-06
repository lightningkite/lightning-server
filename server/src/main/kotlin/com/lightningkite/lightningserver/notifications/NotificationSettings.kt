package com.lightningkite.lightningserver.notifications

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import com.lightningkite.lightningserver.settings.setting
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.io.File


/**
 * NotificationSettings defines and configures how the server will send push notifications to clients.
 * Currently, the only supported ways are Console and FCM/Firebase Messaging.
 * Console will print all notifications straight to the console rather than send them to the client.
 * FCM will use Firebase Messaging to send push notification so clients.
 *
 * @param implementation can be "Console" or "FCM"
 * @param credentials is an api key for communicating with an external service.
 */
@Serializable
data class NotificationSettings(
    val implementation: NotificationImplementation = NotificationImplementation.Console,
    val credentials: String? = null
//) : HealthCheckable {
): ()->NotificationInterface {

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

    override fun invoke(): NotificationInterface = notifications

//    override suspend fun healthCheck(): HealthStatus =
//        when (implementation) {
//            NotificationImplementation.Console -> HealthStatus("Notifications", true)
//            NotificationImplementation.FCM -> {
//                try {
//                    notifications.let {
//
////                    FirebaseAuth.getInstance().createCustomToken("hehe")
//                        HealthStatus("Notifications", true)
//                    }
//                } catch (e: Exception) {
//                    e.printStackTrace()
//                    HealthStatus("Notifications", false, e.message)
//                }
//            }
//        }

}

enum class NotificationImplementation {
    Console, FCM
}

