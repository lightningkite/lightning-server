package com.lightningkite.lightningserver.typed

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.auth.options
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.html
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.services.data.Description
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.childSerializersOrNull
import com.lightningkite.services.database.nullElement
import com.lightningkite.services.database.serializableProperties
import com.lightningkite.services.database.typeParametersSerializersOrNull
import kotlinx.html.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.*


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
    public val kotlin: HttpHandler<PathSpec0> =
        path.path("sdk.zip").get bind HttpHandler {
            HttpResponse(
                TypedData.sink(
                    mediaType = MediaType.Application.Zip,
                    emit = { /*Documentable.kotlinSdk(packageName, it)*/ },
                )
            )
        }

    public val index: HttpHandler<PathSpec0> = path.slash.get bind HttpHandler { _ ->

        val module = serverRuntime.externalSerialization.serializersModule

        @OptIn(ExperimentalSerializationApi::class)
        fun KSerializer<*>.uncontextualize(): KSerializer<*>? {
            return if (this.descriptor.kind == SerialKind.CONTEXTUAL) {
                module.getContextual(
                    descriptor.capturedKClass ?: return null
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
            val arguments: Array<KSerializer<*>> = type.typeParametersSerializersOrNull() ?: arrayOf()
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

                val endpoints = serverRuntime.server.locationedApiHttpHandlers
                val usedTypes = buildSet {
                    fun traverse(type: KSerializer<*>) {
                        if (type in this) return
                        this += type
                        type.childSerializersOrNull()?.forEach { traverse(type) }
                        type.typeParametersSerializersOrNull()?.forEach { traverse(type) }
                    }
                    endpoints.forEach { traverse(it.value.inputType); traverse(it.value.outputType) }
                }
                    .sortedBy {
                        it.descriptor.serialName.substringBefore('<').substringAfterLast('.').substringBefore('/')
                    }
                    .distinctBy {
                        it.descriptor.serialName.substringBefore('<').substringAfterLast('.').substringBefore('/')
                    }

                h2 { +"Endpoints" }
                for ((location, handler) in endpoints) {
                    val (pathSpec, method) = location
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
                                        for ((index, part) in (serializer.childSerializersOrNull()
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

internal val ServerDefinition.locationedApiHttpHandlers: List<Locationed<HttpEndpoint<PathSpec>, ApiHttpHandler<*, *, *, *>>>
    get() = endpoints.entries.flatMap {
        it.value.http.entries
            .filter { it.value is ApiHttpHandler<*, *, *, *> }
            .map { h -> Locationed(HttpEndpoint(it.key, h.key), h.value as ApiHttpHandler<*, *, *, *>) }
    }
        .sortedBy { it.location.run { "$method $path"} }

internal val ServerDefinition.locationedApiWebsocketHandlers: List<Locationed<PathSpec, ApiWebsocketHandler<*, *, *, *, *>>>
    get() = endpoints.entries.mapNotNull {
        (it.value.websocket as? ApiWebsocketHandler<*, *, *, *, *>)
            ?.let { h -> Locationed(it.key, h) }
    }
        .sortedBy { it.location.toString() }