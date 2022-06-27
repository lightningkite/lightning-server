@file:OptIn(InternalSerializationApi::class)

package com.lightningkite.ktorbatteries.typed

import com.lightningkite.ktorbatteries.routes.*
import com.lightningkite.ktorbatteries.serialization.Serialization
import com.lightningkite.ktordb.*
import io.ktor.server.html.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlinx.html.*
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.internal.GeneratedSerializer
import kotlinx.serialization.serializer
import java.util.*
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.reflect.KType
import kotlin.reflect.KVariance
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
                for (api in routes.entries.sortedBy { it.key }.map { it.value }) {
                    h3 {
                        +(api.route.selector.maybeMethod?.value ?: "UNKNOWN")
                        +" "
                        +api.route.fullPath
                        +" - "
                        +api.summary
                    }
                    div {
                        p { +api.description }
                        p {
                            +"Input: "
                            api.inputType?.let {
                                type(it)
                            } ?: run {
                                +"N/A"
                            }
                        }
                        p {
                            +"Output: "
                            api.outputType?.let {
                                type(it)
                            } ?: run {
                                +"N/A"
                            }
                        }
                        p {
                            api.userType?.let {
                                if (it.isMarkedNullable) {
                                    +"You may be authenticated as a: "
                                    type(it)
                                } else {
                                    +"You need to be authenticated as a: "
                                    type(it)
                                }
                            } ?: run {
                                +"This endpoint requires no authentication."
                            }
                        }
                    }
                }

                h2 { +"Types" }

                ApiEndpoint.usedTypes()
                    .sortedBy { it.descriptor.serialName.substringBefore('<').substringAfterLast('.') }
                    .forEach { serializer ->
                        val desc = serializer.descriptor
                        when (desc.kind) {
                            StructureKind.CLASS -> {
                                documentType(serializer) {
                                    for ((index, part) in ((serializer as? GeneratedSerializer<*>)?.childSerializers()
                                        ?: arrayOf()).withIndex()) {
                                        p {
                                            +desc.getElementName(index)
                                            +": "
                                            type(part)
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
        }
    }
}

fun ApiEndpoint.Companion.usedTypes(): Collection<KSerializer<*>> {
    val seen: HashSet<String> = HashSet()
    fun onAllTypes(at: KSerializer<*>, action: (KSerializer<*>) -> Unit) {
        val real = (at.nullElement() ?: at).uncontextualize()
        if (!seen.add(real.descriptor.serialName.substringBefore('<'))) return
        action(real)
        real.subAndChildSerializers().forEach { onAllTypes(it, action) }
    }
    val types = HashMap<String, KSerializer<*>>()
    this.known.asSequence().flatMap {
        sequenceOf(it.inputType, it.outputType)
    }
        .filterNotNull()
        .map { Serialization.json.serializersModule.serializer(it) }
        .forEach { onAllTypes(it) { types[it.descriptor.serialName.substringBefore('<')] = it } }
    return types.values
}

private fun KSerializer<*>.subSerializers(): Array<KSerializer<*>> = listElement()?.let { arrayOf(it) }
    ?: mapValueElement()?.let { arrayOf(it) }
    ?: (this as? GeneratedSerializer<*>)?.typeParametersSerializers()
    ?: (this as? ConditionSerializer<*>)?.inner?.let { arrayOf(it) }
    ?: (this as? ModificationSerializer<*>)?.inner?.let { arrayOf(it) }
    ?: arrayOf()
private fun KSerializer<*>.subAndChildSerializers(): Array<KSerializer<*>> = listElement()?.let { arrayOf(it) }
    ?: mapValueElement()?.let { arrayOf(it) }
    ?: (this as? GeneratedSerializer<*>)?.run { childSerializers() + typeParametersSerializers() }
    ?: (this as? ConditionSerializer<*>)?.inner?.let { arrayOf(it) }
    ?: (this as? ModificationSerializer<*>)?.inner?.let { arrayOf(it) }
    ?: arrayOf()

private fun FlowContent.type(type: KSerializer<*>) {
    type.nullElement()?.let {
        type(it)
        +"?"
        return
    }
    if (type is ContextualSerializer) {
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

private fun FlowContent.type(type: KType) = type(Serialization.json.serializersModule.serializer(type))
private fun KSerializer<*>.uncontextualize(): KSerializer<*> = if (this.descriptor.kind == SerialKind.CONTEXTUAL)
    Serialization.json.serializersModule.getContextual(descriptor.capturedKClass!!)!!
else this

fun FlowContent.documentType(serializer: KSerializer<*>, body: FlowContent.()->Unit) {
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