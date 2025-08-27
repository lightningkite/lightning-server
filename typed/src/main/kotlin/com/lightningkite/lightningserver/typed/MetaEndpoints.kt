package com.lightningkite.lightningserver.typed

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.auth.AnyId
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.builder.bind
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.html
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.Service
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.html.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import java.lang.management.ManagementFactory
import kotlin.math.roundToInt


@Serializable
@GenerateDataClassPaths
public data class ServerHealth(
    val serverId: String,
    val version: String,
    val memory: Memory,
    val features: Map<String, HealthStatus>,
    val loadAverageCpu: Double,
) {
    val overall: HealthStatus.Level get() = features.maxOf { it.value.level }
    public val loadAverageCpuHealth: HealthStatus
        get() = when (val amount = loadAverageCpu) {
            in 0.0..<0.7 -> HealthStatus(HealthStatus.Level.OK)
            in 0.7..<0.95 -> HealthStatus(
                HealthStatus.Level.WARNING,
                additionalMessage = "CPU utilization: ${amount.times(100).roundToInt()}%"
            )

            in 0.95..<1.0 -> HealthStatus(
                HealthStatus.Level.URGENT,
                additionalMessage = "CPU utilization: ${amount.times(100).roundToInt()}%"
            )

            else -> HealthStatus(
                HealthStatus.Level.ERROR,
                additionalMessage = "CPU utilization: ${amount.times(100).roundToInt()}%"
            )
        }

    @Serializable
    @GenerateDataClassPaths
    public data class Memory(
        val max: Long,
        val total: Long,
        val free: Long,
        val systemAllocated: Long,
        val usage: Float,
    ) {
        public val status: HealthStatus
            get() = when (val amount = usage) {
                in 0f..<0.7f -> HealthStatus(HealthStatus.Level.OK)
                in 0.7f..<0.95f -> HealthStatus(
                    HealthStatus.Level.WARNING,
                    additionalMessage = "Memory utilization: ${amount.times(100).roundToInt()}%"
                )

                in 0.95f..<1f -> HealthStatus(
                    HealthStatus.Level.URGENT,
                    additionalMessage = "Memory utilization: ${amount.times(100).roundToInt()}%"
                )

                else -> HealthStatus(
                    HealthStatus.Level.ERROR,
                    additionalMessage = "Memory utilization: ${amount.times(100).roundToInt()}%"
                )
            }
    }
}

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


    private val endpoints: List<Locationed<HttpEndpoint<PathSpec0>, HttpHandler<PathSpec0>>> = listOf(
        docs.index,
        health,
//        isOnline,
//        admin,
//        admin2,
//        openApi,
//        openApiJson,
//        schema,
//        kschema,
//        paths,
//        wsTester
    )

}

public class ApiDocs(private val packageName: String) : ServerBuilder() {

    public val typeScript: Locationed<HttpEndpoint<PathSpec0>, HttpHandler<PathSpec0>> =
        path.path("sdk.ts").get bind HttpHandler {
            HttpResponse(
                TypedData.text(
                    text = buildString { /*Documentable.typescriptSdk2(this)*/ },
                    mediaType = MediaType.Text.Plain
                )
            )
        }

    public val dart: Locationed<HttpEndpoint<PathSpec0>, HttpHandler<PathSpec0>> =
        path.path("sdk.dart").get bind HttpHandler {
            HttpResponse(
                TypedData.text(
                    text = buildString { /*Documentable.dartSdk("sdk.dart", this)*/ },
                    mediaType = MediaType.Text.Plain
                )
            )
        }

    public val kotlin: Locationed<HttpEndpoint<PathSpec0>, HttpHandler<PathSpec0>> =
        path.path("sdk.zip").get bind HttpHandler {
            HttpResponse(
                TypedData.sink(
                    mediaType = MediaType.Application.Zip,
                    emit = { /*Documentable.kotlinSdk(packageName, it)*/ },
                )
            )
        }

//    public val protobuf: Locationed<HttpEndpoint<PathSpec0>, HttpHandler<PathSpec0>> = path.path("sdk.protobuf").get bind HttpHandler {
//        HttpResponse(
//            TypedData.text(
//                text = externalSerialization Serialization.protobuf.schema.generateSchemaText(
//                    Documentable.usedTypes.toList(),
//                    packageName = packageName
//                ),
//                mediaType = MediaType.Application.ProtoBufDeclaration
//            )
//        )
//    }

    public val index: Locationed<HttpEndpoint<PathSpec0>, HttpHandler<PathSpec0>> = path.get bind HttpHandler { _ ->
        HttpResponse(body = TypedData.html {
            head { title("${generalSettings().projectName} - Generated Documentation") }
            body {
                h1 { +"API Docs" }
                div {
                    h2 { +"Links" }
                    ol {
                        li { a(href = "sdk.ts") { +"Typescript SDK" } }
                        li { a(href = "sdk.zip") { +"Kotlin SDK" } }
                        li { a(href = "sdk.protobuf") { +"Protobuf Types" } }
                        li { a(href = "sdk.dart") { +"Dart SDK" } }
                        li { a(href = "#types") { +"Types" } }
                    }
                }
                h2 { +"Endpoints" }
//                for (api in Documentable.endpoints.sortedBy { it.path.toString() }) {
//                    h3 {
//                        +(api.route.method.toString())
//                        +" "
//                        +api.route.path.path.toString()
//                        +" - "
//                        +api.summary
//                    }
//                    div {
//                        p { +api.description }
//                        p {
//                            +"Input: "
//                            api.inputType.let {
//                                type(it)
//                            }
//                        }
//                        p {
//                            +"Output: "
//                            api.outputType.let {
//                                type(it)
//                            }
//                        }
//                        p {
//                            +"You need to be authenticated as a: "
//                            +api.authOptions.options.joinToString {
//                                if (it == null) "no authentication" else it.type.authName ?: "???"
//                            }
//                        }
//                    }
//                }
//
//                h2 {
//                    id = "types"
//                    +"Types"
//                }
//
//                h3 { +"Types stored directly in the database" }
//
//                ul {
//                    Documentable.usedTypes
//                        .sortedBy { it.descriptor.serialName.substringBefore('<').substringAfterLast('.') }
//                        .forEach { serializer ->
//                            val desc = serializer.descriptor
//                            when (desc.kind) {
//                                StructureKind.CLASS -> {
//                                    if (desc.elementNames.none { it == "_id" }) return@forEach
//                                    val baseName = desc.serialName.substringBefore('<').substringAfterLast('.')
//                                    li { a(href = "#$baseName") { +baseName } }
//                                }
//
//                                else -> {}
//                            }
//                        }
//                }
//
//                h3 { +"Index" }
//
//                ul {
//                    Documentable.usedTypes
//                        .sortedBy { it.descriptor.serialName.substringBefore('<').substringAfterLast('.') }
//                        .forEach { serializer ->
//                            val desc = serializer.descriptor
//                            when (desc.kind) {
//                                StructureKind.CLASS,
//                                SerialKind.ENUM,
//                                PrimitiveKind.BOOLEAN,
//                                PrimitiveKind.STRING,
//                                PrimitiveKind.BYTE,
//                                PrimitiveKind.CHAR,
//                                PrimitiveKind.SHORT,
//                                PrimitiveKind.INT,
//                                PrimitiveKind.LONG,
//                                PrimitiveKind.FLOAT,
//                                PrimitiveKind.DOUBLE,
//                                StructureKind.LIST,
//                                StructureKind.MAP,
//                                    -> {
//                                    val baseName = desc.serialName.substringBefore('<').substringAfterLast('.')
//                                    li { a(href = "#$baseName") { +baseName } }
//                                }
//
//                                else -> {}
//                            }
//                        }
//                }
//
//                Documentable.usedTypes
//                    .sortedBy { it.descriptor.serialName.substringBefore('<').substringAfterLast('.') }
//                    .forEach { serializer ->
//                        val desc = serializer.descriptor
//                        when (desc.kind) {
//                            StructureKind.CLASS -> {
//                                documentType(serializer) {
//                                    serializer.serializableProperties?.toList()?.forEachIndexed { index, item ->
//                                        p {
//                                            +item.name
//                                            +": "
//                                            type(item.serializer)
//                                            desc.getElementAnnotations(index).filterIsInstance<Description>()
//                                                .firstOrNull()?.let {
//                                                +" - "
//                                                +it.text
//                                            }
//                                        }
//                                    } ?: run {
//                                        for ((index, part) in ((serializer as? GeneratedSerializer<*>)?.childSerializers()
//                                            ?: arrayOf()).withIndex()) {
//                                            p {
//                                                +desc.getElementName(index)
//                                                +": "
//                                                type(part)
//                                                desc.getElementAnnotations(index).filterIsInstance<Description>()
//                                                    .firstOrNull()?.let {
//                                                    +" - "
//                                                    +it.text
//                                                }
//                                            }
//                                        }
//                                    }
//                                }
//                            }
//
//                            SerialKind.ENUM -> {
//                                documentType(serializer) {
//                                    p {
//                                        +"A string containing one of the following values:"
//                                    }
//                                    ul {
//                                        for (index in 0 until desc.elementsCount) {
//                                            li {
//                                                +desc.getElementName(index)
//                                            }
//                                        }
//                                    }
//                                }
//                            }
//
//                            PrimitiveKind.BOOLEAN -> {
//                                documentType(serializer) {
//                                    +"A JSON boolean, either true or false."
//                                }
//                            }
//
//                            PrimitiveKind.STRING -> {
//                                documentType(serializer) {
//                                    +"A JSON string."
//                                }
//                            }
//
//                            PrimitiveKind.BYTE,
//                            PrimitiveKind.CHAR,
//                            PrimitiveKind.SHORT,
//                            PrimitiveKind.INT,
//                            PrimitiveKind.LONG,
//                            PrimitiveKind.FLOAT,
//                            PrimitiveKind.DOUBLE,
//                                -> {
//                                documentType(serializer) {
//                                    +"A JSON number."
//                                }
//                            }
//
//                            StructureKind.LIST -> {
//                                documentType(serializer) {
//                                    +"A JSON array."
//                                }
//                            }
//
//                            StructureKind.MAP -> {
//                                documentType(serializer) {
//                                    +"A JSON object, also known as a map or dictionary."
//                                }
//                            }
//
//                            else -> {}
//                        }
//                    }
            }
        })
    }
}