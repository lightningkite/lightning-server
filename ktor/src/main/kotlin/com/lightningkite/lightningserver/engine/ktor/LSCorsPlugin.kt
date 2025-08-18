package com.lightningkite.lightningserver.engine.ktor

import com.lightningkite.lightningserver.definition.CorsSettings
import com.lightningkite.lightningserver.runtime.ServerRuntimeBase
import com.lightningkite.lightningserver.runtime.generateCorsHeaders
import com.lightningkite.lightningserver.runtime.generatePreflightCorsHeaders
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*

internal fun ServerRuntimeBase.getLSCorsPlugin(cors: CorsSettings): RouteScopedPlugin<LSCorsConfig> =
    createRouteScopedPlugin("LS_CORS", ::LSCorsConfig) {
        onCall { call ->
            if (call.response.isCommitted) {
                return@onCall
            }

            if (call.request.httpMethod == HttpMethod.Options) {
                cors.generatePreflightCorsHeaders(call.request.headers.adapt())
                    .forEach { (key, values) -> values.forEach { value -> call.response.header(key, value.root) } }
                call.respond(HttpStatusCode.NoContent)
                return@onCall
            }

            cors.generateCorsHeaders(call.request.headers.adapt())
                .forEach { (key, values) -> values.forEach { value -> call.response.header(key, value.root) } }
        }
    }


internal class LSCorsConfig

