package com.lightningkite.ktorbatteries.serialization

import com.lightningkite.ktorbatteries.files.MultipartJsonConverter
import com.lightningkite.ktorkmongo.HtmlApiContentConverter
import com.lightningkite.ktorkmongo.JsonWebSockets
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.serialization.*

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Serialization.json)
        register(ContentType.MultiPart.FormData, MultipartJsonConverter(Serialization.json))
        val conv = HtmlApiContentConverter(Serialization.json)
        register(conv.contentType, conv)
    }
    install(JsonWebSockets) {
        json = Serialization.json
    }
}
