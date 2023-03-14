package com.lightningkite.lightningserver.notifications

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.firebase.messaging.Notification as FCMNotification
import java.io.File


/**
 * The concrete implementation of NotificationInterface that will use Firebase Messaging to send push notifications to
 * clients.
 */
object FcmNotificationInterface : NotificationInterface {
    init {
        NotificationSettings.register("fcm") {
            var creds = it.credentials?.trim() ?: throw IllegalStateException(
                "FCM was selected for notification implementation, but no credential file was provided."
            )
            if(!creds.startsWith('{')) {
                val file = File(creds)
                assert(file.exists()) { "FCM credentials file not found at '$file'" }
                creds = file.readText()
            }
            FirebaseApp.initializeApp(
                FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(creds.byteInputStream()))
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
        data: NotificationData
    ) {
        val notification = data.notification
        val android = data.android
        val ios = data.ios
        val web = data.web
        val programmaticData = data.data
        val builder = with(MulticastMessage.builder()) {
            if (programmaticData != null)
                putAllData(programmaticData)
            setApnsConfig(
                with(ApnsConfig.builder()) {
                    if (notification != null) {
                        setFcmOptions(
                            ApnsFcmOptions
                                .builder()
                                .setImage(notification.imageUrl)
                                .build()
                        )
                    }
                    setAps(with(Aps.builder()) {
                        if (ios != null) {
                            if (ios.critical && ios.sound != null)
                                setSound(
                                    CriticalSound.builder()
                                        .setCritical(true)
                                        .setName(ios.sound)
                                        .setVolume(1.0)
                                        .build()
                                )
                            else {
                                setSound(ios.sound)
                            }
                        } else {
                            setSound("default")
                        }
                        build()
                    })
                    build()
                }
            )
            if (android != null)
                setAndroidConfig(
                    with(AndroidConfig.builder()) {
                        setPriority(android.priority.toAndroid())
                        setNotification(
                            AndroidNotification.builder()
                                .setChannelId(android.channel)
                                .setSound(android.sound)
                                .build()
                        )
                        build()
                    }
                )
            setWebpushConfig(
                with(
                    WebpushConfig
                        .builder()
                ) {
                    if (web != null) {
                        putAllData(web.data)
                    }
                    if (notification != null)
                        setNotification(
                            WebpushNotification.builder()
                                .setTitle(notification.title)
                                .setBody(notification.body)
                                .setImage(notification.imageUrl)
                                .build()
                        )
                    build()
                }
            )
            if (notification != null) {
                setNotification(
                    FCMNotification.builder()
                        .setTitle(notification.title)
                        .setBody(notification.body)
                        .setImage(notification.imageUrl)
                        .build()
                )
            }
            this
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


private fun NotificationPriority.toAndroid(): AndroidConfig.Priority = when (this) {
    NotificationPriority.HIGH -> AndroidConfig.Priority.HIGH
    NotificationPriority.NORMAL -> AndroidConfig.Priority.NORMAL
}