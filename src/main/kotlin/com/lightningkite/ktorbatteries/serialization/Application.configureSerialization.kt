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
        // This multipart converter does not work. If you can find documentation on how to make it work or
        // an example than you got further than I could.
//        register(ContentType.MultiPart.FormData, MultipartJsonConverter(Serialization.json))
        val conv = HtmlApiContentConverter(Serialization.json)
        register(conv.contentType, conv)
    }
    install(JsonWebSockets) {
        json = Serialization.json
    }
}
