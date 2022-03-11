package com.lightningkite.ktorbatteries.notifications

import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MulticastMessage

interface NotificationInterface {
    suspend fun send(
        targets: List<String>,
        title: String? = null,
        body: String? = null,
        imageUrl: String? = null,
        data: Map<String, String>? = null,
    )

    suspend fun send(
        message: Message
    )

    suspend fun send(
        message: MulticastMessage
    )
}