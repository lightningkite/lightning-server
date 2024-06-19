package com.lightningkite.lightningserver.notifications

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.*
import com.lightningkite.lightningserver.exceptions.report
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.google.firebase.messaging.Notification as FCMNotification


/**
 * The concrete implementation of NotificationClient that will use Firebase Messaging to send push notifications to
 * clients.
 */
object FcmNotificationClient : NotificationClient {
    init {
        NotificationSettings.register("fcm") {

            var creds = it.credentials?.trim()
                ?: it.implementation.substringAfter("://", "").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException(
                    "FCM was selected for notifications, but no credentials were provided."
                )

            if (!creds.startsWith('{')) {
                val file = File(creds)
                assert(file.exists()) { "FCM credentials file not found at '$file'" }
                creds = file.readText()
            }
            FirebaseApp.initializeApp(
                FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(creds.byteInputStream()))
                    .build()
            )
            FcmNotificationClient
        }
    }

    /**
     * Sends a simple notification and data. No custom options are set beyond what is provided.
     * If you need a more complicated set of messages you should use the other functions.
     */
    override suspend fun send(
        targets: List<String>,
        data: NotificationData
    ): Map<String, NotificationSendResult> {
        val notification = data.notification
        val android = data.android
        val ios = data.ios
        val web = data.web
        val programmaticData = data.data
        fun builder() = with(MulticastMessage.builder()) {
            if (programmaticData != null)
                putAllData(programmaticData)
            notification?.link?.let { putData("link", it) }
            setApnsConfig(
                with(ApnsConfig.builder()) {
                    data.timeToLive?.let {
                        this.putHeader("apns-expiration", it.toString())
                    }
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
                        data.timeToLive?.let {
                            setTtl(it.inWholeSeconds)
                        }
                        setNotification(
                            AndroidNotification.builder()
                                .setChannelId(android.channel)
                                .setSound(android.sound)
                                .setClickAction(data.notification?.link)
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
                    if (notification != null) {
                        notification.link?.let {
                            setFcmOptions(WebpushFcmOptions.withLink(it))
                        }
                        setNotification(
                            WebpushNotification.builder()
                                .setTitle(notification.title)
                                .setBody(notification.body)
                                .setImage(notification.imageUrl)
                                .build()
                        )
                    }
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

        val results = HashMap<String, NotificationSendResult>()
        val errorCodes = HashSet<MessagingErrorCode>()
        targets
            .chunked(500)
            .map {
                builder()
                    .addAllTokens(it)
                    .build()
            }
            .forEach {
                withContext(Dispatchers.IO) {
                    val result = FirebaseMessaging.getInstance().sendEachForMulticast(it)
                    result.responses.forEachIndexed { index, sendResponse ->
                        println("Send $index: ${sendResponse.messageId}")
                        results[targets[index]] = when(val it = sendResponse.exception?.messagingErrorCode) {
                            null -> NotificationSendResult.Success
                            MessagingErrorCode.UNREGISTERED -> NotificationSendResult.DeadToken
                            else -> {
                                errorCodes.add(it)
                                NotificationSendResult.Failure
                            }
                        }
                    }
                }
            }
        if(errorCodes.isNotEmpty()) {
            Exception("Some notifications failed to send.  Error codes received: ${errorCodes.joinToString()}")
                .report()
        }
        return results
    }

}


private fun NotificationPriority.toAndroid(): AndroidConfig.Priority = when (this) {
    NotificationPriority.HIGH -> AndroidConfig.Priority.HIGH
    NotificationPriority.NORMAL -> AndroidConfig.Priority.NORMAL
}