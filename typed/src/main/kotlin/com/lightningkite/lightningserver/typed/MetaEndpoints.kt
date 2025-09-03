package com.lightningkite.lightningserver.typed

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.auth.AnyId
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.auth.options
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.builder.bind
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.html
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathMoved
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.Service
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.Description
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.nullElement
import com.lightningkite.services.database.serializableProperties
import com.lightningkite.services.database.tryChildSerializers
import com.lightningkite.services.database.tryTypeParameterSerializers3
import com.lightningkite.services.http.client
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.html.*
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.internal.GeneratedSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import java.lang.management.ManagementFactory
import kotlin.collections.sortedBy


public class MetaEndpoints(
    private val packageName: String,
    private val database: RuntimeDeferred<Database>,
    private val cache: RuntimeDeferred<Cache>,
) : ServerBuilder() {

    public val root: Locationed<HttpEndpoint<PathSpec0>, HttpHandler<PathSpec0>> = path.get bind HttpHandler {
        HttpResponse(body = TypedData.html {
            head { title("${generalSettings().projectName} - Meta Information") }
            body {
                ul {
                    for (endpoint in endpoints) {
                        li {
                            a(href = endpoint.location.path.toString()) {
                                +endpoint.location.path.segments.last().toString()
                            }
                        }
                    }
                }
            }
        })
    }

    public val isOnline: Locationed<HttpEndpoint<PathSpec0>, HttpHandler<PathSpec0>> =
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
        val max = Runtime.getRuntime().maxMemory().roundMemoryForSecurity()
        val total = Runtime.getRuntime().totalMemory().roundMemoryForSecurity()
        val free = Runtime.getRuntime().freeMemory().roundMemoryForSecurity()
        return ServerHealth.Memory(
            max = max,
            total = total,
            free = free,
            systemAllocated = total - free,
            usage = ((total - free).toDouble() / max.toDouble()).toFloat()
        )
    }

    public val docs: ApiDocs = path.path("docs") bind ApiDocs(packageName)

    public val health: Locationed<HttpEndpoint<PathSpec0>, ApiHttpHandler<PathSpec0, HasId<AnyId>?, Unit, ServerHealth>> =
        path.path("health").get bind ApiHttpHandler(
            auth = noAuth,
            inputType = Unit.serializer(),
            outputType = ServerHealth.serializer(),
            summary = "Get Server Health",
            description = "Gets the current status of the server",
            errorCases = listOf(),
            implementation = { _: Unit ->
                serverHealth(
                    features = settings
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


    context(server: ServerRuntime)
    private suspend fun openAdmin(): HttpResponse {
        val inject = buildJsonObject {
            put("url", generalSettings().publicUrl)
        }
        val page = client.get("https://lsadmin.cs.lightningkite.com").bodyAsText()
            .let { original ->
                (original.substringBeforeLast("</body>") + """
                    <script type="application/json" id="injectedBackendInformation">${inject}</script>
                    </body>
                """.trimIndent() + original.substringAfterLast("</body>"))
            }
            .let { original ->
                (original.substringBeforeLast("<head>") + """
                    <head>
                    <base href="${TODO()}">
                """.trimIndent() + original.substringAfterLast("<head>"))
            }
        return HttpResponse.html(content = page, headers = HttpHeaders {
            set(
                "Content-Security-Policy",
                "script-src 'unsafe-eval' ${generalSettings().publicUrl}/ https://lsadmin.cs.lightningkite.com/"
            )
        })
    }

    public val admin: Locationed<HttpEndpoint<PathSpec0>, HttpHandler<PathSpec0>> =
        path.path("admin").get bind HttpHandler {
            openAdmin()
        }

    public val adminResources: Locationed<HttpEndpoint<PathSpec0>, HttpHandler<PathSpec0>> =
        path.path("admin").any.get bind HttpHandler {
            val wildcard = it.path.pathInContext.wildcard?.toString()
            if (wildcard?.contains('.') == true)
                HttpResponse.pathMoved("https://lsadmin.cs.lightningkite.com/${wildcard}")
            else
                openAdmin()
        }

    public val schema: Locationed<HttpEndpoint<PathSpec0>, HttpHandler<PathSpec0>> =
        path.path("schema").get bind HttpHandler {
            HttpResponse(
                body = TypedData.text(
                    "{}"/*externalSerialization.json.encodeToString(lightningServerSchema)*/,
                    MediaType.Application.Json
                ),
                status = HttpStatus.OK
            )
        }

    public val kschema: Locationed<HttpEndpoint<PathSpec0>, HttpHandler<PathSpec0>> =
        path.path("kschema").get bind HttpHandler {
            HttpResponse(
                body = TypedData.text(
                    "{}"/*externalSerialization.json.encodeToString(lightningServerKSchema)*/,
                    MediaType.Application.Json
                )
            )
        }

    public val openApi: Locationed<HttpEndpoint<PathSpec0>, HttpHandler<PathSpec0>> =
        path.path("openapi").get bind HttpHandler {
            when (it.headers.accept.firstOrNull()) {
                MediaType.Text.Html -> HttpResponse.html(
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
                              spec: ${"{}"/*externalSerialization.json.encodeToString(openApiDescription)*/},
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
                        "{}"/*externalSerialization.json.encodeToString(openApiDescription)*/,
                        MediaType.Application.Json
                    ),
                    status = HttpStatus.OK
                )
            }
        }
    public val openApiJson: Locationed<HttpEndpoint<PathSpec0>, HttpHandler<PathSpec0>> =
        path.path("openapi.json").get bind HttpHandler {
            HttpResponse(
                body = TypedData.text(
                    "{}"/*externalSerialization.json.encodeToString(openApiDescription)*/,
                    MediaType.Application.Json
                ),
                status = HttpStatus.OK
            )
        }
    public val paths: Locationed<HttpEndpoint<PathSpec0>, HttpHandler<PathSpec0>> =
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

    public val wsTester: Locationed<HttpEndpoint<PathSpec0>, HttpHandler<PathSpec0>> =
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
              </body>
            </html>
        """.trimIndent()

            )
        }

    private val endpoints: List<Locationed<HttpEndpoint<PathSpec0>, HttpHandler<PathSpec0>>> = listOf(
        docs.index,
        isOnline,
        health,
        admin,
        schema,
        kschema,
        openApi,
        openApiJson,
        paths,
        wsTester
    )

}

public class ApiDocs(private val packageName: String) : ServerBuilder() {

//    public val typeScript: Locationed<HttpEndpoint<PathSpec0>, HttpHandler<PathSpec0>> =
//        path.path("sdk.ts").get bind HttpHandler {
//            HttpResponse(
//                TypedData.text(
//                    text = buildString { /*Documentable.typescriptSdk2(this)*/ },
//                    mediaType = MediaType.Text.Plain
//                )
//            )
//        }
//
//    public val dart: Locationed<HttpEndpoint<PathSpec0>, HttpHandler<PathSpec0>> =
//        path.path("sdk.dart").get bind HttpHandler {
//            HttpResponse(
//                TypedData.text(
//                    text = buildString { /*Documentable.dartSdk("sdk.dart", this)*/ },
//                    mediaType = MediaType.Text.Plain
//                )
//            )
//        }
//
//    public val kotlin: Locationed<HttpEndpoint<PathSpec0>, HttpHandler<PathSpec0>> =
//        path.path("sdk.zip").get bind HttpHandler {
//            HttpResponse(
//                TypedData.sink(
//                    mediaType = MediaType.Application.Zip,
//                    emit = { /*Documentable.kotlinSdk(packageName, it)*/ },
//                )
//            )
//        }

    public val index: Locationed<HttpEndpoint<PathSpec0>, HttpHandler<PathSpec0>> = path.get bind HttpHandler { _ ->

        val module = serverRuntime.externalSerialization.serializersModule

        @OptIn(ExperimentalSerializationApi::class)
        fun KSerializer<*>.uncontextualize(): KSerializer<*>? {
            return if (this.descriptor.kind == SerialKind.CONTEXTUAL) {
                module.getContextual(
                    descriptor.capturedKClass ?: throw IllegalStateException("No captured KClass found for $descriptor")
                )
            } else this
        }

        fun FlowContent.documentType(serializer: KSerializer<*>, body: FlowContent.() -> Unit) {
            val desc = serializer.descriptor
            val name = desc.serialName.substringBefore('<').substringAfterLast('.').substringBefore('/')
            details {
                summary {
                    id = name
                    +(name)
                }
                desc.annotations.filterIsInstance<Description>().firstOrNull()?.let {
                    div {
                        it.text.trimIndent().split('\n').forEach {
                            p { +it }
                        }
                    }
                }
                body()
            }
        }

        fun FlowContent.type(type: KSerializer<*>) {
            type.nullElement()?.let {
                type(it)
                +"?"
                return
            }
            if (type.descriptor.kind == SerialKind.CONTEXTUAL) {
                type.uncontextualize()?.let { type(it) }
                return
            }
            val baseName = type.descriptor.serialName.substringBefore('<').substringAfterLast('.')
            val arguments: Array<KSerializer<*>> = type.tryTypeParameterSerializers3() ?: arrayOf()
            span {
                a(href = "#$baseName") {
                    +(baseName)
                }
                arguments.takeUnless { it.isEmpty() }?.let {
                    +"<"
                    var first = true
                    it.forEach {
                        if (first) first = false
                        else +", "
                        type(it)
                    }
                    +">"
                }
            }
        }

        HttpResponse(body = TypedData.html {
            head { title("${generalSettings().projectName} - Generated Documentation") }
            body {
                h1 { +"API Docs" }
                div {
                    h2 { +"Links" }
                    ol {
//                        li { a(href = "sdk.ts") { +"Typescript SDK" } }
//                        li { a(href = "sdk.zip") { +"Kotlin SDK" } }
//                        li { a(href = "sdk.protobuf") { +"Protobuf Types" } }
//                        li { a(href = "sdk.dart") { +"Dart SDK" } }
                        li { a(href = "#types") { +"Types" } }
                    }
                }

                val endpoints = serverRuntime.server.endpoints.entries.flatMap {
                    it.value.http.entries
                        .filter { it.value is ApiHttpHandler<*, *, *, *> }
                        .map { h -> Triple(it.key, h.key, h.value as ApiHttpHandler<*, *, *, *>) }
                }
                    .sortedWith(Comparator.comparing<Triple<PathSpec, HttpMethod, HttpHandler<*>>, String> { it.first.toString() }
                        .thenBy { it.second.toString() })
                val usedTypes = buildSet {
                    fun traverse(type: KSerializer<*>) {
                        if (type in this) return
                        this += type
                        type.tryChildSerializers()?.forEach { traverse(type) }
                        type.tryTypeParameterSerializers3()?.forEach { traverse(type) }
                    }
                    endpoints.forEach { traverse(it.third.inputType); traverse(it.third.outputType) }
                }
                    .sortedBy {
                        it.descriptor.serialName.substringBefore('<').substringAfterLast('.').substringBefore('/')
                    }
                    .distinctBy {
                        it.descriptor.serialName.substringBefore('<').substringAfterLast('.').substringBefore('/')
                    }

                h2 { +"Endpoints" }
                for ((pathSpec, method, handler) in endpoints) {
                    details {
                        summary {
                            +method.toString()
                            +" "
                            +pathSpec.toString()
                            +" - "
                            +handler.summary
                        }
                        p { +handler.description }
                        p {
                            +"Input: "
                            handler.inputType.let {
                                type(it)
                            }
                        }
                        p {
                            +"Output: "
                            handler.outputType.let {
                                type(it)
                            }
                        }
                        p {
                            +"You need to be authenticated as a: "
                            +handler.auth.options().joinToString()
                        }
                    }
                }

                h2 {
                    id = "types"
                    +"Types"
                }

                h3 { +"Types stored directly in the database" }

                ul {
                    usedTypes
                        .forEach { serializer ->
                            val desc = serializer.descriptor
                            when (desc.kind) {
                                StructureKind.CLASS -> {
                                    if (desc.elementNames.none { it == "_id" }) return@forEach
                                    val baseName = desc.serialName.substringBefore('<').substringAfterLast('.')
                                    li { a(href = "#$baseName") { +baseName } }
                                }

                                else -> {}
                            }
                        }
                }

                h3 { +"Index" }

                ul {
                    usedTypes
                        .forEach { serializer ->
                            val desc = serializer.descriptor
                            when (desc.kind) {
                                StructureKind.CLASS,
                                SerialKind.ENUM,
                                PrimitiveKind.BOOLEAN,
                                PrimitiveKind.STRING,
                                PrimitiveKind.BYTE,
                                PrimitiveKind.CHAR,
                                PrimitiveKind.SHORT,
                                PrimitiveKind.INT,
                                PrimitiveKind.LONG,
                                PrimitiveKind.FLOAT,
                                PrimitiveKind.DOUBLE,
                                StructureKind.LIST,
                                StructureKind.MAP,
                                    -> {
                                    val baseName = desc.serialName.substringBefore('<').substringAfterLast('.')
                                    li { a(href = "#$baseName") { +baseName } }
                                }

                                else -> {}
                            }
                        }
                }

                usedTypes
                    .forEach { serializer ->
                        val desc = serializer.descriptor
                        when (desc.kind) {
                            StructureKind.CLASS -> {
                                documentType(serializer) {
                                    serializer.serializableProperties?.toList()?.forEachIndexed { index, item ->
                                        p {
                                            +item.name
                                            +": "
                                            type(item.serializer)
                                            desc.getElementAnnotations(index).filterIsInstance<Description>()
                                                .firstOrNull()?.let {
                                                    +" - "
                                                    +it.text
                                                }
                                        }
                                    } ?: run {
                                        for ((index, part) in (serializer.tryChildSerializers()
                                            ?: arrayOf()).withIndex()) {
                                            p {
                                                +desc.getElementName(index)
                                                +": "
                                                type(part)
                                                desc.getElementAnnotations(index).filterIsInstance<Description>()
                                                    .firstOrNull()?.let {
                                                        +" - "
                                                        +it.text
                                                    }
                                            }
                                        }
                                    }
                                }
                            }

                            SerialKind.ENUM -> {
                                documentType(serializer) {
                                    p {
                                        +"A string containing one of the following values:"
                                    }
                                    ul {
                                        for (index in 0 until desc.elementsCount) {
                                            li {
                                                +desc.getElementName(index)
                                            }
                                        }
                                    }
                                }
                            }

                            PrimitiveKind.BOOLEAN -> {
                                documentType(serializer) {
                                    +"A JSON boolean, either true or false."
                                }
                            }

                            PrimitiveKind.STRING -> {
                                documentType(serializer) {
                                    +"A JSON string."
                                }
                            }

                            PrimitiveKind.BYTE,
                            PrimitiveKind.CHAR,
                            PrimitiveKind.SHORT,
                            PrimitiveKind.INT,
                            PrimitiveKind.LONG,
                            PrimitiveKind.FLOAT,
                            PrimitiveKind.DOUBLE,
                                -> {
                                documentType(serializer) {
                                    +"A JSON number."
                                }
                            }

                            StructureKind.LIST -> {
                                documentType(serializer) {
                                    +"A JSON array."
                                }
                            }

                            StructureKind.MAP -> {
                                documentType(serializer) {
                                    +"A JSON object, also known as a map or dictionary."
                                }
                            }

                            else -> {}
                        }
                    }
            }
        })
    }
}