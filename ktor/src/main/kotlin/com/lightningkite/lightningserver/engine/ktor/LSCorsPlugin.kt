package com.lightningkite.lightningserver.engine.ktor

import com.lightningkite.lightningserver.runtime.ServerRuntimeBase
import com.lightningkite.lightningserver.runtime.generateCorsHeaders
import com.lightningkite.lightningserver.runtime.generatePreflightCorsHeaders
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*

internal fun ServerRuntimeBase.getLSCorsPlugin(): RouteScopedPlugin<LSCorsConfig> =
    createRouteScopedPlugin("LS_CORS", ::LSCorsConfig) {
        val corsSettings = this@getLSCorsPlugin.settings.get(ktorRunConfig, this@getLSCorsPlugin).cors
        if (corsSettings != null)
            onCall { call ->
                if (call.response.isCommitted) {
                    return@onCall
                }

                if (call.request.httpMethod == HttpMethod.Options) {
                    corsSettings.generatePreflightCorsHeaders(call.request.headers.adapt())
                        .forEach { (key, values) -> values.forEach { value -> call.response.header(key, value.root) } }
                    call.respond(HttpStatusCode.NoContent)
                    return@onCall
                }

                corsSettings.generateCorsHeaders(call.request.headers.adapt())
                    .forEach { (key, values) -> values.forEach { value -> call.response.header(key, value.root) } }
            }
    }


internal class LSCorsConfig

