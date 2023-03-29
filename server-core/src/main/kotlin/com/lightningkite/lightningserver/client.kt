package com.lightningkite.lightningserver

import com.lightningkite.lightningserver.serialization.Serialization
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.decodeFromString

val client = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Serialization.jsonWithoutDefaults)
    }
    engine {
        this.requestTimeout = 60000
    }
}

class HttpResponseException(val response: HttpResponse, val body: String): Exception("Got response ${response.status}: ${body.take(300)}")
suspend fun HttpResponse.statusFailing(): HttpResponse {
    if (!this.status.isSuccess()) throw HttpResponseException(this, bodyAsText())
    return this
}

suspend inline fun <reified T> HttpResponse.debugJsonBody(): T {
    val text = bodyAsText()
    logger.debug("Got response ${status} with data $text")
    return Serialization.json.decodeFromString(text)
}

fun HttpMessageBuilder.json() {
    accept(ContentType.Application.Json)
    contentType(ContentType.Application.Json)
}