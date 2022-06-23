package com.lightningkite.ktorbatteries.typed

import com.lightningkite.ktorbatteries.routes.*
import com.lightningkite.ktorbatteries.serialization.Serialization
import io.ktor.server.html.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlinx.html.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.serializer
import kotlin.reflect.typeOf

@KtorDsl
fun Route.apiHelp(path: String = "docs") = route(path) {
    val rootRoute = this
    val routes = ApiEndpoint.known.associateBy { it.route.fullPath + "/" + it.route.selector.maybeMethod?.value }
    get {
        context.respondHtml {
            head { title("Index") }
            body {
                h1 { +"API Docs" }
                h2 { +"Endpoints" }
                for(api in routes.entries.sortedBy { it.key }.map { it.value }) {
                    h3 {
                        +(api.route.selector.maybeMethod?.value ?: "UNKNOWN")
                        +" "
                        +api.route.fullPath
                        + " - "
                        +api.summary
                    }
                    div {
                        p { +api.description }
                        p {
                            +"Input: "
                            a(href = "#${api.inputType}") {
                                +(api.inputType.toString())
                            }
                        }
                        p {
                            +"Output: "
                            a(href = "#${api.outputType}") {
                                +(api.outputType.toString())
                            }
                        }
                        p { +"You need to be authenticated as a: ${api.userType}" }
                    }
                }

                h2 { +"Types" }
                val skipSet = setOf(
                    "Query",
                    "MassModification",
                    "EntryChange",
                    "ListChange",
                    "Modification",
                    "Condition"
                )
                val seen: HashSet<SerialDescriptor> = HashSet()
                fun onAllTypes(at: SerialDescriptor, action: (SerialDescriptor)->Unit) {
                    if(!seen.add(at)) return
                    val real = if(at.kind == SerialKind.CONTEXTUAL)
                        Serialization.json.serializersModule.getContextualDescriptor(at)!!
                    else
                        at
                    if(real.serialName.startsWith("com.lightningkite.ktordb") || real.serialName in skipSet) return
                    action(real)
                    real.elementDescriptors.forEach { onAllTypes(it, action) }
                }
                val types = HashSet<SerialDescriptor>()
                routes.values.asSequence().flatMap {
                    sequenceOf(it.inputType, it.outputType)
                }
                    .filterNotNull()
                    .map { Serialization.json.serializersModule.serializer(it).descriptor }
                    .map {
                        if(it.kind == SerialKind.CONTEXTUAL)
                            Serialization.json.serializersModule.getContextualDescriptor(it)!!
                        else
                            it
                    }
                    .forEach { onAllTypes(it) { types.add(it) } }
                types
                    .filter { !it.serialName.endsWith("?") }
                    .forEach { desc ->
                        when(desc.kind) {
                            is StructureKind.CLASS -> {
                                h3 {
                                    id = desc.serialName
                                    +(desc.serialName)
                                }
                                div {
                                    for(index in 0 until desc.elementsCount) {
                                        p {
                                            +desc.getElementName(index)
                                            +": "
                                            val typeName = desc.getElementDescriptor(index).serialName.substringBefore('<')
                                            a(href = typeName) {
                                                +typeName
                                            }
                                        }
                                    }
                                }
                            }
                            is SerialKind.ENUM -> {
                                h3 {
                                    id = desc.serialName
                                    +(desc.serialName)
                                }
                                div {
                                    for(index in 0 until desc.elementsCount) {
                                        p {
                                            +desc.getElementName(index)
                                        }
                                    }
                                }
                            }
                        }
                    }
            }
        }
    }
}
