@file:OptIn(InternalSerializationApi::class)

package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningdb.Description
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.generalSettings
import kotlinx.html.*
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.internal.GeneratedSerializer
//import kotlinx.serialization.protobuf.schema.ProtoBufSchemaGenerator
import kotlinx.serialization.serializer
import kotlin.reflect.KType
import com.lightningkite.serialization.*

@Deprecated(
    "Use apiDocs instead",
    ReplaceWith("this.apiDocs(packageName)", "com.lightningkite.lightningserver.typed.apiDocs")
)
@LightningServerDsl
fun ServerPath.apiHelp(packageName: String = "com.mypackage"): HttpEndpoint = apiDocs(packageName)

@LightningServerDsl
fun ServerPath.apiDocs(packageName: String = "com.mypackage"): HttpEndpoint {
    get("sdk.ts").handler {
        HttpResponse(
            HttpContent.Text(
                string = buildString { Documentable.typescriptSdk(this) },
                type = ContentType.Text.Plain
            )
        )
    }
    get("sdk.dart").handler {
        HttpResponse(
            HttpContent.Text(
                string = buildString { Documentable.dartSdk("sdk.dart", this) },
                type = ContentType.Text.Plain
            )
        )
    }
    get("sdk.zip").handler {
        HttpResponse(
            HttpContent.OutStream(
                write = { Documentable.kotlinSdk(packageName, it) },
                type = ContentType.Application.Zip
            )
        )
    }
//    get("sdk.protobuf").handler {
//        HttpResponse(
//            HttpContent.Text(
//                string = ProtoBufSchemaGenerator.generateSchemaText(
//                    Documentable.usedTypes.map { it.descriptor },
//                    packageName = packageName
//                ),
//                type = ContentType.Application.ProtoBufDeclaration
//            )
//        )
//    }
    return this.copy(after = ServerPath.Afterwards.TrailingSlash).get.handler { _ ->
        HttpResponse(body = HttpContent.html {
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
                for (api in Documentable.endpoints.sortedBy { it.path.toString() }) {
                    h3 {
                        +(api.route.method.toString())
                        +" "
                        +api.route.path.path.toString()
                        +" - "
                        +api.summary
                    }
                    div {
                        p { +api.description }
                        p {
                            +"Input: "
                            api.inputType.let {
                                type(it)
                            }
                        }
                        p {
                            +"Output: "
                            api.outputType.let {
                                type(it)
                            }
                        }
                        p {
                            +"You need to be authenticated as a: "
                            +api.authOptions.options.joinToString { if(it == null) "no authentication" else it.type.authName ?: "???" }
                        }
                    }
                }

                h2 {
                    id = "types"
                    +"Types"
                }

                h3 { +"Types stored directly in the database" }

                ul {
                    Documentable.usedTypes
                        .sortedBy { it.descriptor.serialName.substringBefore('<').substringAfterLast('.') }
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
                    Documentable.usedTypes
                        .sortedBy { it.descriptor.serialName.substringBefore('<').substringAfterLast('.') }
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
                                StructureKind.MAP -> {
                                    val baseName = desc.serialName.substringBefore('<').substringAfterLast('.')
                                    li { a(href = "#$baseName") { +baseName } }
                                }

                                else -> {}
                            }
                        }
                }

                Documentable.usedTypes
                    .sortedBy { it.descriptor.serialName.substringBefore('<').substringAfterLast('.') }
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
                                            desc.getElementAnnotations(index).filterIsInstance<Description>().firstOrNull()?.let {
                                                +" - "
                                                +it.text
                                            }
                                        }
                                    } ?: run {
                                        for ((index, part) in ((serializer as? GeneratedSerializer<*>)?.childSerializers()
                                            ?: arrayOf()).withIndex()) {
                                            p {
                                                +desc.getElementName(index)
                                                +": "
                                                type(part)
                                                desc.getElementAnnotations(index).filterIsInstance<Description>().firstOrNull()?.let {
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
                            PrimitiveKind.DOUBLE -> {
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

fun FlowContent.documentType(serializer: KSerializer<*>, body: FlowContent.() -> Unit) {
    val desc = serializer.descriptor
    val name = desc.serialName.substringBefore('<').substringAfterLast('.')
    h3 {
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

private fun FlowContent.type(type: KSerializer<*>) {
    type.nullElement()?.let {
        type(it)
        +"?"
        return
    }
    if (type.descriptor.kind == SerialKind.CONTEXTUAL) {
        type(type.uncontextualize())
        return
    }
    val baseName = type.descriptor.serialName.substringBefore('<').substringAfterLast('.')
    val arguments: Array<KSerializer<*>> = type.subSerializers()
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
