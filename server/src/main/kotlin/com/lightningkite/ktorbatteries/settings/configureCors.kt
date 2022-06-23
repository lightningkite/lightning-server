package com.lightningkite.ktorbatteries.settings

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*

/**
 * A shortcut function to set up CORS using the cors value from GeneralServerSettings.
 * For every domain listed in GeneralServerSettings cors it will allow that domain and the schemas: "http", "https", "ws", "wss"
 * If cors is null AND debug is set to true it will all anyHost
 * It will add allowed methods: Options, Put, Patch, Delete
 * It will add allowed headers: "ContentType", "Authorization"
 *
 * @param customHeaders Each value will be added as an allowed custom header.
 */
fun Application.configureCors(customHeaders: List<String>? = null) {
    install(CORS) {

        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)

        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        customHeaders?.forEach {
            allowHeader(it)
        }

        GeneralServerSettings.instance.cors?.forEach {
            allowHost(it, listOf("http", "https", "ws", "wss"))
        } ?: if (GeneralServerSettings.instance.debug) anyHost()
    }
}