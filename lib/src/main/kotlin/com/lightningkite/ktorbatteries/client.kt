package com.lightningkite.ktorbatteries

import com.lightningkite.ktorbatteries.serialization.Serialization
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*

val client = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Serialization.json)
    }
}