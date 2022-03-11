package com.lightningkite.ktorbatteries.notifications

import com.google.firebase.messaging.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object FcmNotificationInterface : NotificationInterface {
    /**
     * Sends a simple notification and data. No custom options are set beyond what is provided.
     */
    override suspend fun send(
        targets: List<String>,
        title: String?,
        body: String?,
        imageUrl: String?,
        data: Map<String, String>?,
    ) {
        targets.chunked(500)
            .map {
                MulticastMessage.builder()
                    .apply {
                        addAllTokens(it)
                        putAllData(data)
                        if (title != null || body != null || imageUrl != null) {
                            setApnsConfig(
                                ApnsConfig
                                    .builder()
                                    .setFcmOptions(
                                        ApnsFcmOptions
                                            .builder()
                                            .setImage(imageUrl)
                                            .build()
                                    )
                                    .build()
                            )
                            setWebpushConfig(
                                WebpushConfig
                                    .builder()
                                    .setNotification(
                                        WebpushNotification.builder()
                                            .setImage(imageUrl)
                                            .build()
                                    )
                                    .build()
                            )
                            setNotification(
                                Notification.builder()
                                    .setTitle(title)
                                    .setBody(body)
                                    .setImage(imageUrl)
                                    .build()
                            )
                        }
                    }
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

