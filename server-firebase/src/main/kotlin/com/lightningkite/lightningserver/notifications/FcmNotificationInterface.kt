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
 * The concrete implementation of NotificationInterface that will use Firebase Messaging to send push notifications to clients.
 */
object FcmNotificationInterface : NotificationInterface {
    init {
        NotificationSettings.register("fcm") {
            var creds = it.credentials?.trim() ?: throw IllegalStateException("FCM was selected for notification implementation, but no credential file was provided.")
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
                        com.google.firebase.messaging.Notification.builder()
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


    override suspend fun send(
        targets: List<String>,
        notification: Notification?,
        data: Map<String, String>?,
        android: Android?,
        ios: iOS?,
        web: Web?
    ) {
        val builder = with(MulticastMessage.builder()) {
            if (data != null)
                putAllData(data)
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
                        if (ios != null)
                            if (ios.critical && ios.sound != null)
                                setSound(
                                    CriticalSound.builder()
                                        .setCritical(true)
                                        .setName(ios.sound)
                                        .setVolume(0.5)
                                        .build()
                                )
                            else {
                                setSound(ios.sound)
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


private fun Priority.toAndroid(): AndroidConfig.Priority = when (this) {
    Priority.HIGH -> AndroidConfig.Priority.HIGH
    Priority.NORMAL -> AndroidConfig.Priority.NORMAL
}