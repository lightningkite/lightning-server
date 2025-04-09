package com.lightningkite.lightningserver.ktor

import com.lightningkite.lightningserver.cors.generateCorsHeaders
import com.lightningkite.lightningserver.cors.generatePreflightCorsHeaders
import com.lightningkite.lightningserver.settings.CorsSettings
import com.lightningkite.lightningserver.settings.generalSettings
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.PluginBuilder
import io.ktor.server.application.RouteScopedPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respond


val LS_CORS: RouteScopedPlugin<LSCorsConfig> = createRouteScopedPlugin("LS_CORS", ::LSCorsConfig) {
    buildPlugin()
}

class LSCorsConfig

internal fun PluginBuilder<LSCorsConfig>.buildPlugin() {

    val corsSettings = generalSettings().cors
    if (corsSettings != null)
        onCall { call ->
            if (call.response.isCommitted) {
                return@onCall
            }

            if (call.request.httpMethod == HttpMethod.Options) {
                corsSettings.generatePreflightCorsHeaders(call.request.headers.adapt())
                    .entries
                    .forEach { (key, value) -> call.response.header(key, value) }
                call.respond(HttpStatusCode.NoContent)
                return@onCall
            }

            corsSettings.generateCorsHeaders(call.request.headers.adapt())
                .entries
                .forEach { (key, value) -> call.response.header(key, value) }
        }
}
