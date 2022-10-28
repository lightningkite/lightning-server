package com.lightningkite.lightningserver.notifications


enum class Priority {
    HIGH,
    NORMAL
}

data class Android(
    val channel: String? = null,
    val priority: Priority = Priority.NORMAL,
    val sound:String? = null,
)

data class Notification(
    val title: String?,
    val body: String?,
    val imageUrl: String?,
)

data class iOS(
    val critical: Boolean = false,
    val sound: String? = null
)

data class Web(
    val data: Map<String, String>,
)


interface NotificationInterface {
    suspend fun send(
        targets: List<String>,
        title: String? = null,
        body: String? = null,
        imageUrl: String? = null,
        data: Map<String, String>? = null,
        critical: Boolean = false,
        androidChannel: String? = null
    )

    suspend fun send(
        targets: List<String>,
        notification: Notification? = null,
        data: Map<String, String>? = null,
        android: Android? = null,
        ios: iOS? = null,
        web: Web? = null,
    )
}