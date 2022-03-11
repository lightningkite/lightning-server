package com.lightningkite.ktorbatteries.notifications

import com.google.firebase.messaging.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object FcmNotificationInterface : NotificationInterface {
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
                if (critical)
                    setAndroidConfig(
                        AndroidConfig.builder()
                            .setPriority(AndroidConfig.Priority.HIGH)
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

        targets.chunked(500)
            .map {
                builder
                    .addAllTokens(it)
                    .build()
            }
            .forEach { FirebaseMessaging.getInstance().sendMulticast(it) }
    }

    override suspend fun send(message: Message) {
        FirebaseMessaging.getInstance().send(message)
    }

    override suspend fun send(message: MulticastMessage) {
        FirebaseMessaging.getInstance().sendMulticast(message)
    }
}

