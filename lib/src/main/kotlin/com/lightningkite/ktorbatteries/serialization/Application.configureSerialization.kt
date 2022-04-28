package com.lightningkite.ktorbatteries.serialization

import com.lightningkite.ktorbatteries.files.MultipartJsonConverter
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Serialization.json)
        // This multipart converter does not work. If you can find documentation on how to make it work or
        // an example than you got further than I could.
        register(ContentType.MultiPart.FormData, MultipartJsonConverter(Serialization.json))
    }
}
