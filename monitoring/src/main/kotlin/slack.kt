package com.lightningkite.lightningserver.monitoring

import com.lightningkite.lightningserver.client
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put


suspend fun slack(channel: String, message: String) {
    println("Slack: $message")
    Server.slack()?.let {
        client.post("https://slack.com/api/chat.postMessage") {
            bearerAuth(it)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("text", message)
                put("channel", channel)
            }.toString())
        }
    }
}