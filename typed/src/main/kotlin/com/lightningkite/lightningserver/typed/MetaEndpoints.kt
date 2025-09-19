package com.lightningkite.lightningserver.typed

import com.lightningkite.*
import com.lightningkite.DataSize.Companion.bytes
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.instrument
import com.lightningkite.lightningserver.typed.jsonschema.*
import com.lightningkite.lightningserver.typed.kschema.*
import com.lightningkite.services.*
import com.lightningkite.services.cache.*
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import com.lightningkite.services.http.client
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.*
import kotlinx.html.*
import kotlinx.html.html
import kotlinx.serialization.builtins.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.lang.Runtime
import java.lang.management.*
import kotlin.text.contains
import kotlin.text.set
import kotlin.time.TimeSource


public class MetaEndpoints(
    private val packageName: String,
    private val database: RuntimeDeferred<Database>,
    private val cache: RuntimeDeferred<Cache>,
) : ServerBuilder() {

    public val root: HttpHandler<PathSpec0> = path.slash.get bind HttpHandler {
        HttpResponse(body = TypedData.html {
            head { title("${generalSettings().projectName} - Meta Information") }
            body {
                ul {
                    for (endpoint in endpoints) {
                        li {
                            a(href = endpoint.location.path.toString()) {
                                +endpoint.location.path.segments.last { it != PathSpec.Segment.Empty }.toString()
                            }
                        }
                    }
                }
            }
        })
    }

    public val isOnline: HttpHandler<PathSpec0> =
        path.path("online").get bind HttpHandler { HttpResponse.plainText("Server is running.") }

    context(server: ServerRuntime)
    private fun serverHealth(
        features: Map<String, HealthStatus>,
    ): ServerHealth = ServerHealth(
        serverId = server.serverId,
        version = server.serverVersion,
        memory = memory(),
        features = features,
        loadAverageCpu = ManagementFactory.getOperatingSystemMXBean().systemLoadAverage / ManagementFactory.getOperatingSystemMXBean().availableProcessors,
    )

    private fun Long.roundMemoryForSecurity() = this.div(100_000).times(100_000)  // Round to the nearest megabyte
    private fun memory(): ServerHealth.Memory {
        val max = Runtime.getRuntime().maxMemory().roundMemoryForSecurity().bytes
        val total = Runtime.getRuntime().totalMemory().roundMemoryForSecurity().bytes
        val free = Runtime.getRuntime().freeMemory().roundMemoryForSecurity().bytes
        return ServerHealth.Memory(
            max = max,
            total = total,
            free = free,
            systemAllocated = total - free,
            usage = ((total - free).bytes.toDouble() / max.bytes.toDouble()).toFloat()
        )
    }

    public val docs: ApiDocs = path.path("docs") include ApiDocs(packageName)

    public val health: ApiHttpHandler<PathSpec0, HasId<AnyId>?, Unit, ServerHealth> =
        path.path("health").get bind explicitApiHttpHandler(
            auth = noAuth,
            inputType = Unit.serializer(),
            outputType = ServerHealth.serializer(),
            summary = "Get Server Health",
            description = "Gets the current status of the server",
            errorCases = listOf(),
            implementation = { _: Unit ->
                serverHealth(
                    features = serverRuntime.settings.settings
                        .map { it() }
                        .mapNotNull { it ->
                            val checkable =
                                it as? Service ?: return@mapNotNull null
                            it.name to checkable
                        }
                        .associate { it }
                        .mapValues { (key, checkable) ->
                            cache.await().get(key, HealthStatus.serializer())
                                ?.takeIf { now() - it.checkedAt < checkable.healthCheckFrequency }
                                ?: withTimeoutOrNull(10_000L) { checkable.healthCheck() }?.also {
                                    cache.await().set(
                                        key,
                                        it,
                                        HealthStatus.serializer(),
                                        timeToLive = checkable.healthCheckFrequency
                                    )
                                }
                                ?: HealthStatus(
                                    HealthStatus.Level.ERROR,
                                    additionalMessage = "Timed out after 10 seconds."
                                )

                        }
                )
            }
        )

    public val kschema: HttpHandler<PathSpec0> =
        path.path("kschema").get bind HttpHandler {
            HttpResponse(
                body = TypedData.text(
                    serverRuntime.externalSerialization.json.encodeToString(lightningServerKSchema),
                    MediaType.Application.Json
                )
            )
        }

    context(runtime: ServerRuntime)
    private suspend fun openAdmin2(): HttpResponse {
        val inject = buildJsonObject {
            put("url", generalSettings().publicUrl)
        }
        val page = client.get("https://ls5admin.cs.lightningkite.com").bodyAsText()
            .let { original ->
                (original.substringBeforeLast("</body>") + """
                    <script type="application/json" id="injectedBackendInformation">${inject}</script>
                    </body>
                """.trimIndent() + original.substringAfterLast("</body>"))
            }
            .let { original ->
                (original.substringBeforeLast("<head>") + """
                    <head>
                    <base href="${admin2.location.path.concrete().toString(runtime.externalSerialization.stringArrayFormat)}">
                """.trimIndent() + original.substringAfterLast("<head>"))
            }
        return HttpResponse.html(content = page, headers = HttpHeaders {
            set(
                "Content-Security-Policy",
                "script-src 'unsafe-eval' ${generalSettings().publicUrl}/ https://ls5admin.cs.lightningkite.com/"
            )
        })
    }
    public val admin2: HttpHandler<PathSpec0> = path.path("admin2").slash.get bind HttpHandler {
        openAdmin2()
    }
    public val admin2Resources: HttpHandler<PathSpec0> = path.path("admin2").any.get bind HttpHandler {
        if (it.trailingSegments?.any { it.contains(".") } == true)
            HttpResponse.pathMovedOld("https://ls5admin.cs.lightningkite.com/${it.trailingSegments}")
        else
            openAdmin2()
    }

    public val openApi: HttpHandler<PathSpec0> =
        path.path("openapi").get bind HttpHandler {
            val desiredFormat = it.headers.accept.firstOrNull() ?: MediaType.Application.Json
            when {
                desiredFormat.accepts(MediaType.Text.Html) -> HttpResponse.html(
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
                              spec: ${serverRuntime.externalSerialization.jsonWithoutDefaults.encodeToString(openApiDescription)},
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
                    body = TypedData.text(
                        serverRuntime.externalSerialization.jsonWithoutDefaults.encodeToString(openApiDescription),
                        MediaType.Application.Json
                    ),
                    status = HttpStatus.OK
                )
            }
        }
    public val openApiJson: HttpHandler<PathSpec0> =
        path.path("openapi.json").get bind HttpHandler {
            HttpResponse(
                body = TypedData.text(
                    serverRuntime.externalSerialization.jsonWithoutDefaults.encodeToString(openApiDescription),
                    MediaType.Application.Json
                ),
                status = HttpStatus.OK
            )
        }
    public val paths: HttpHandler<PathSpec0> =
        path.path("paths").get bind HttpHandler {
            val definition = contextOf<ServerRuntime>().server
            HttpResponse(body = TypedData.html {
                head { title("${generalSettings().projectName} - Path List") }
                body {
                    ul {
                        for (endpoints in definition.endpoints.entries.sortedBy { it.key.toString() }) {
                            endpoints.value.http.entries.sortedBy { it.key.toString() }.forEach { (method, handler) ->
                                li { a(href = endpoints.key.toString()) { +"$method ${endpoints.key}" } }
                            }
                            endpoints.value.websocket?.let { handler ->
                                li { a(href = wsTester.location.toString() + "?path=${endpoints.key}") { +"WS ${endpoints.key}" } }
                            }
                        }
                        for (schedule in definition.schedules) {
                            li { +"SCHEDULE ${schedule.key}: ${schedule.value.schedule}" }
                        }
                        for (task in definition.tasks) {
                            li { +"TASK ${task.key}: ${task.value.serializer.descriptor.serialName}" }
                        }
                    }
                }
            })
        }

    public val wsTester: HttpHandler<PathSpec0> =
        path.path("ws-tester").get bind HttpHandler {
            //language=HTML
            HttpResponse.html(
                content =
                    """
            <!DOCTYPE html>
            <html>
              <head>
                <meta name="robots" content="noindex">
                <meta charset="utf-8">
                <title>${generalSettings().projectName}</title>
              </head>
              <body>
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
                <label>Path <input id='path' value='${it.queryParameters.get("path") ?: "/"}'/></label>
                <button type='button' onclick='connectClick()'>Connect</button>
                <button type='button' onclick='closeClick()'>Close</button>
            </div>
            <div>
                <label>Message <textarea id='msg'></textarea></label>
                <button type='button' onclick='sendClick()'>Send</button>
            </div>
            <button type='button' onclick='clearClick()'>clear</button>
            <div id='messages'></div>
              </body>
            </html>
        """.trimIndent()

            )
        }

    public val bulk: ApiHttpHandler<PathSpec0, HasId<AnyId>?, Map<String, BulkRequest>, Map<String, BulkResponse>> = path.path("bulk").post bind ApiHttpHandler(
        summary = "Bulk Request",
        description = "Performs multiple requests at once, returning the results in the same order.",
        auth = noAuth,
        implementation = { requests: Map<String, BulkRequest> ->
            val originalRequest = request
            coroutineScope {
                requests.entries.map { entry ->
                    async {
                        val start = TimeSource.Monotonic.markNow()
                        val request = entry.value
                        val pathAndParams = PathAndParams.parse(request.path)
                        val properRequest = originalRequest.copyWithNewPathType(
                            path = RawHttpEndpoint<PathSpec>(pathAndParams.pathSegments, method = HttpMethod(request.method)),
                            queryParameters = pathAndParams.queryParameters,
                            body = request.body?.let { TypedData.text(it, MediaType.Application.Json) }
                        )
                        try {
                            entry.key to instrument(properRequest.path.toString()) {
                                (@Suppress("UNCHECKED_CAST")
                                (properRequest.path.match.value as HttpHandler<PathSpec>).handle(properRequest))
                            }.let {
                                BulkResponse(
                                    durationMs = start.elapsedNow().inWholeMilliseconds,
                                    result = it.body?.text()
                                )
                            }
                        } catch (e: Exception) {
                            entry.key to BulkResponse(
                                durationMs = start.elapsedNow().inWholeMilliseconds,
                                error = when (e) {
                                    is HttpStatusException -> e.toLSError()
                                    else -> LSError(500, "unknown", "An unknown server error occurred.")
                                }
                            )
                        }
                    }
                }.awaitAll().associate { it }
            }
        }
    )

    private val endpoints: List<HttpHandler<PathSpec0>> = listOf(
        docs.index,
        admin2,
        isOnline,
        health,
        kschema,
        openApi,
        openApiJson,
        paths,
        wsTester

    )

}
