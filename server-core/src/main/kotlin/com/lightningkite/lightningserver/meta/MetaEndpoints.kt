package com.lightningkite.lightningserver.meta

import com.lightningkite.lightningserver.HtmlDefaults
import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.jsonschema.lightningServerSchema
import com.lightningkite.lightningserver.jsonschema.openApiDescription
import com.lightningkite.lightningserver.routes.fullUrl
import com.lightningkite.lightningserver.schedule.Scheduler
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serverhealth.healthCheck
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.typed.apiDocs
import com.lightningkite.lightningserver.websocket.WebSockets
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.html.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class MetaEndpoints<USER>(
    path: ServerPath,
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
    val health = path("health").healthCheck()
    val isOnline = path("online").get.handler { HttpResponse.plainText("Server is running.") }

    private suspend fun openAdmin(): HttpResponse {
        val inject = buildJsonObject {
            put("url", generalSettings().publicUrl)
            put("basePage", path("admin/").toString())
        }
        val original = client.get("https://lightning-server-admin.s3.us-west-2.amazonaws.com/index.html").bodyAsText()
        val page = (original.substringBeforeLast("</body>") + """
            <script type="application/json" id="injectedBackendInformation">${inject}</script>
            </body>
        """.trimIndent() + original.substringAfterLast("</body>"))
            .replace("/static/", admin.path.toString() + "static/")
        return HttpResponse.html(content = page, headers = {
            set(
                "Content-Security-Policy",
                "script-src 'unsafe-eval' ${generalSettings().publicUrl}/ https://lightning-server-admin.s3.us-west-2.amazonaws.com/"
            )
        })
    }

    val admin = path("admin/").get.handler {
        openAdmin()
    }
    val adminResources = path("admin/{...}").get.handler {
        if (it.wildcard?.contains(".") == true)
            HttpResponse.pathMovedOld("https://lightning-server-admin.s3.us-west-2.amazonaws.com/${it.wildcard}")
        else
            openAdmin()
    }
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
                    <!DOCTYPE html>
                    <html>
                      <head>
                        <meta charset="utf-8" />
                        <meta name="viewport" content="width=device-width, initial-scale=1" />
                        <meta
                          name="description"
                          content="SwaggerUI"
                        />
                        <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@4.5.0/swagger-ui.css" />
                        <style>
                          .topbar {
                            display: none;
                          }
                        </style>
                      </head>
    
                      <body>
                        <div id="swagger-ui"></div>
                        <script src="https://unpkg.com/swagger-ui-dist@4.5.0/swagger-ui-bundle.js" crossorigin></script>
                        <script src="https://unpkg.com/swagger-ui-dist@4.5.0/swagger-ui-standalone-preset.js" crossorigin></script>
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
                    for (endpoint in Http.endpoints.keys.sortedBy { it.path.toString() }) {
                        li { a(href = endpoint.path.fullUrl()) { +endpoint.toString() } }
                    }
                    for (path in WebSockets.handlers.keys) {
                        li { a(href = wsTester.path.toString() + "?path=${path}") { +"WS $path" } }
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
    val wsTester = get("ws-tester").handler {
        //language=HTML
        HttpResponse.html(
            content = HtmlDefaults.basePage(
                """
            <script>
            /** @type {WebSocket | null} **/
            let ws = null
            function getCookie(name) {
              var match = document.cookie.match(new RegExp('(^| )' + name + '=([^;]+)'));
              if (match) return match[2];
            }
            function connectClick() {
                /** @type {HTMLInputElement} **/
                const pathElement = document.getElementById("path") 
                const messagesElement = document.getElementById("messages")
                const token = getCookie("Authorization")
                const url = "${generalSettings().wsUrl}" + pathElement.value + (token ? "?jwt=" + token : "")
                console.log(url)
                ws = new WebSocket(url, url.substring(0, url.indexOf("://")))
                ws.addEventListener('open', ev => {
                    const newElement = document.createElement('p')
                    newElement.innerText = 'WS Opened.'
                    messagesElement.appendChild(newElement)
                })
                ws.addEventListener('error', ev => {
                    const newElement = document.createElement('p')
                    newElement.innerText = 'WS Error!'
                    messagesElement.appendChild(newElement)
                })
                ws.addEventListener('message', ev => {
                    const newElement = document.createElement('p')
                    newElement.innerText = 'IN: ' + ev.data
                    messagesElement.appendChild(newElement)
                })
                ws.addEventListener('close', ev => {
                    const newElement = document.createElement('p')
                    newElement.innerText = 'WS Closed.'
                    messagesElement.appendChild(newElement)
                })
            }
            function sendClick() {
                if(ws === null) return
                /** @type {HTMLTextAreaElement} **/
                const msgElement = document.getElementById("msg") 
                ws.send(msgElement.value)
                const messagesElement = document.getElementById("messages") 
                const newElement = document.createElement('p')
                newElement.innerText = 'OUT: ' + msgElement.value
                messagesElement.appendChild(newElement)
                msgElement.value = ""
            }
            function closeClick() {
                if(ws === null) return
                ws.close()
            }
            function clearClick() {
                const messagesElement = document.getElementById("messages") 
                messages.innerHTML = ''
            }
            </script>
            <div>
                <label>Path <input id='path' value='${it.queryParameter("path") ?: "/"}'/></label>
                <button type='button' onclick='connectClick()'>Connect</button>
                <button type='button' onclick='closeClick()'>Close</button>
            </div>
            <div>
                <label>Message <textarea id='msg'></textarea></label>
                <button type='button' onclick='sendClick()'>Send</button>
            </div>
            <button type='button' onclick='clearClick()'>clear</button>
            <div id='messages'></div>
        """.trimIndent()
            )
        )
    }
    val endpoints = listOf<HttpEndpoint>(
        docs,
        health.route.endpoint,
        isOnline,
        admin,
        openApi,
        openApiJson,
        schema,
        paths,
        wsTester
    )
}

inline fun <reified USER> ServerPath.metaEndpoints(
    packageName: String = "com.mypackage",
    noinline isAdmin: suspend (USER) -> Boolean,
): MetaEndpoints<USER> = MetaEndpoints(this, packageName, isAdmin)