package com.lightningkite.lightningserver.meta

import com.lightningkite.lightningserver.auth.AuthInfo
import com.lightningkite.lightningserver.auth.jwt
import com.lightningkite.lightningserver.auth.rawUser
import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.adminIndex
import com.lightningkite.lightningserver.files.UploadEarlyEndpoint
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.jsonschema.*
import com.lightningkite.lightningserver.routes.fullUrl
import com.lightningkite.lightningserver.schedule.Scheduler
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serverhealth.healthCheck
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.websocket.WebSockets
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.html.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class MetaEndpoints<USER>(
    path: ServerPath,
    val authInfo: AuthInfo<USER>,
    packageName: String = "com.mypackage",
    isAdmin: suspend (USER) -> Boolean,
) : ServerPathGroup(path) {
    val root = get.handler {
        HttpResponse(body = HttpContent.Html {
            head { title("${generalSettings().projectName} - Meta Information") }
            body {
                ul {
                    for (endpoint in endpoints) {
                        li { a(href = endpoint.path.fullUrl()) { +endpoint.path.segments.last().toString() } }
                    }
                }
            }
        })
    }
    val docs = path("docs").apiDocs(packageName)
    val health = path("health").healthCheck(authInfo, isAdmin)
    val isOnline = path("online").get.handler { HttpResponse.plainText("Server is running.") }

    private suspend fun openAdmin(jwt: String?): HttpResponse {
        val inject = buildJsonObject {
            put("url", generalSettings().publicUrl)
            put("basePage", path("admin/").toString())
            jwt?.let {
                put("jwt", it)
            }
        }
        val original = client.get("https://lightning-server-admin.s3.us-west-2.amazonaws.com/index.html").bodyAsText()
        val page = (original.substringBeforeLast("</body>") + """
            <script type="application/json" id="injectedBackendInformation">${inject}</script>
            </body>
        """.trimIndent() + original.substringAfterLast("</body>"))
            .replace("/static/", admin.path.toString() + "/static/")
        return HttpResponse.html(content = page, headers = {
            set(
                "Content-Security-Policy",
                "script-src 'unsafe-eval' ${generalSettings().publicUrl}/ https://lightning-server-admin.s3.us-west-2.amazonaws.com/"
            )
        })
    }

    val admin = path("admin/").get.handler {
        openAdmin(it.jwt())
    }
    val adminResources = path("admin/{...}").get.handler {
        if (it.wildcard?.contains(".") == true)
            HttpResponse.pathMovedOld("https://lightning-server-admin.s3.us-west-2.amazonaws.com/${it.wildcard}")
        else
            openAdmin(it.jwt())
    }
    val adminIndex = path("admin-index").adminIndex()
    val schema = path("schema").get.handler {
        HttpResponse(
            body = HttpContent.Text(
                Serialization.jsonWithoutDefaults.encodeToString(lightningServerSchema),
                ContentType.Application.Json
            ),
            status = HttpStatus.OK
        )
    }
    val openApi = path("openapi").get.handler {
        when (it.headers.accept.firstOrNull()) {
            ContentType.Text.Html -> HttpResponse.html(
                content = """
                <html>
                  <head>
                    <meta charset="UTF-8">
                    <link rel="stylesheet" type="text/css" href="https://cdnjs.cloudflare.com/ajax/libs/swagger-ui/3.19.5/swagger-ui.css" >
                    <style>
                      .topbar {
                        display: none;
                      }
                    </style>
                  </head>

                  <body>
                    <div id="swagger-ui"></div>
                    <script src="https://cdnjs.cloudflare.com/ajax/libs/swagger-ui/3.19.5/swagger-ui-bundle.js"> </script>
                    <script src="https://cdnjs.cloudflare.com/ajax/libs/swagger-ui/3.19.5/swagger-ui-standalone-preset.js"> </script>
                    <script>
                      window.onload = function() {
                        const ui = SwaggerUIBundle({
                          spec: ${Serialization.jsonWithoutDefaults.encodeToString(openApiDescription)},
                          dom_id: '#swagger-ui',
                          deepLinking: true,
                          presets: [
                            SwaggerUIBundle.presets.apis,
                            SwaggerUIStandalonePreset
                          ],
                          plugins: [
                            SwaggerUIBundle.plugins.DownloadUrl
                          ],
                          layout: "StandaloneLayout"
                        })
                     
                        window.ui = ui
                      }
                  </script>
                  </body>
                </html>
            """.trimIndent()
            )

            else -> HttpResponse(
                body = HttpContent.Text(
                    Serialization.jsonWithoutDefaults.encodeToString(openApiDescription),
                    ContentType.Application.Json
                ),
                status = HttpStatus.OK
            )
        }
    }
    val openApiJson = path("openapi.json").get.handler {
        HttpResponse(
            body = HttpContent.Text(
                Serialization.jsonWithoutDefaults.encodeToString(openApiDescription),
                ContentType.Application.Json
            ),
            status = HttpStatus.OK
        )
    }
    val paths = get("paths").handler {
        HttpResponse(body = HttpContent.Html {
            head { title("${generalSettings().projectName} - Path List") }
            body {
                ul {
                    for (endpoint in Http.endpoints.keys) {
                        li { a(href = endpoint.path.fullUrl()) { +endpoint.toString() } }
                    }
                    for (path in WebSockets.handlers.keys) {
                        li { a(href = path.fullUrl()) { +"WS $path" } }
                    }
                    for (schedule in Scheduler.schedules) {
                        li { +"SCHEDULE ${schedule.key}: ${schedule.value.schedule}" }
                    }
                    for (task in Tasks.tasks) {
                        li { +"TASK ${task.key}: ${task.value.serializer.descriptor.serialName}" }
                    }
                }
            }
        })
    }
    val endpoints = listOf<HttpEndpoint>(
        docs,
        health.route,
        isOnline,
        admin,
        adminIndex,
        openApi,
        openApiJson,
        schema,
        paths,
    )
}

inline fun <reified USER> ServerPath.metaEndpoints(
    packageName: String = "com.mypackage",
    noinline isAdmin: suspend (USER) -> Boolean,
): MetaEndpoints<USER> = MetaEndpoints(this, AuthInfo(), packageName, isAdmin)