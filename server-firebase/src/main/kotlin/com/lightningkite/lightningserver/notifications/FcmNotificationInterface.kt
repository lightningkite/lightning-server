package com.lightningkite.lightningserver.notifications

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.*
import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File


/**
 * The concrete implementation of NotificationInterface that will use Firebase Messaging to send push notifications to clients.
 */
object FcmNotificationInterface : NotificationInterface {
    init {
        NotificationSettings.register("fcm") {
            assert(it.credentials != null) { "FCM was selected for notification implementation, but no credential file was provided." }
            val file = File(it.credentials!!)
            assert(file.exists()) { "FCM credentials file not found at '$file'" }
            FirebaseApp.initializeApp(
                FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(file.inputStream()))
                    .build()
            )
            FcmNotificationInterface
        }
    }

    /**
     * Sends a simple notification and data. No custom options are set beyond what is provided.
     * If you need a more complicated set of messages you should use the other functions.
     */
    override suspend fun send(
        targets: List<String>,
        title: String?,
        body: String?,
        imageUrl: String?,
        data: Map<String, String>?,
        critical: Boolean,
        androidChannel: String?,
    ) {
        val includeNotification = title != null || body != null || imageUrl != null

        val builder = MulticastMessage.builder()
            .apply {
                if (data != null)
                    putAllData(data)
                setApnsConfig(
                    ApnsConfig
                        .builder()
                        .apply {
                            if (includeNotification)
                                setFcmOptions(
                                    ApnsFcmOptions
                                        .builder()
                                        .setImage(imageUrl)
                                        .build()
                                )

                            setAps(
                                Aps.builder()
                                    .apply {
                                        if (critical)
                                            setSound(
                                                CriticalSound.builder()
                                                    .setCritical(true)
                                                    .setName("default")
                                                    .setVolume(1.0)
                                                    .build()
                                            )
                                    }
                                    .build()
                            )
                        }
                        .build()
                )
                if (includeNotification)
                    setWebpushConfig(
                        WebpushConfig
                            .builder()
                            .setNotification(
                                WebpushNotification.builder()
                                    .setTitle(title)
                                    .setBody(body)
                                    .setImage(imageUrl)
                                    .build()
                            )
                            .build()
                    )
                if (critical || androidChannel != null)
                    setAndroidConfig(
                        AndroidConfig.builder()
                            .apply {
                                if (critical)
                                    setPriority(AndroidConfig.Priority.HIGH)
                                if (androidChannel != null)
                                    setNotification(
                                        AndroidNotification.builder()
                                            .setChannelId(androidChannel)
                                            .build()
                                    )
                            }
                            .build()
                    )
                if (includeNotification)
                    setNotification(
                        Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .setImage(imageUrl)
                            .build()
                    )
            }

        targets
            .chunked(500)
            .map {
                builder
                    .addAllTokens(it)
                    .build()
            }
            .forEach {
                withContext(Dispatchers.IO) {
                    FirebaseMessaging.getInstance().sendMulticast(it)
                }
            }
    }
}