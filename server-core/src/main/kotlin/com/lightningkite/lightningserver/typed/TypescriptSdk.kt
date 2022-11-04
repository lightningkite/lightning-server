@file:OptIn(InternalSerializationApi::class)

package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningdb.ServerFile
import com.lightningkite.lightningdb.listElement
import com.lightningkite.lightningdb.mapValueElement
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningdb.nullElement
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.Http
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.websocket.WebSockets
import io.ktor.http.*
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.internal.GeneratedSerializer
import kotlinx.serialization.serializer
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.KType

fun Documentable.Companion.typescriptSdk(out: Appendable) = with(out) {
    val safeDocumentables = endpoints.filter { it.inputType == Unit.serializer() || it.route.method != HttpMethod.GET }.toList()
    appendLine("import { ${skipSet.joinToString()}, apiCall, Path } from '@lightningkite/lightning-server-simplified'")
    appendLine()
    usedTypes
        .sortedBy { it.descriptor.serialName.substringBefore('<').substringAfterLast('.') }
        .filter { it.descriptor.serialName.substringBefore('<').substringAfterLast('.') !in skipSet }
        .forEach {
            when(it.descriptor.kind) {
                is StructureKind.CLASS -> {
                    append("export interface ")
                    it.write().let { out.append(it) }
                    appendLine(" {")
                    (it as? GeneratedSerializer<*>)?.childSerializers()?.forEachIndexed { index, sub ->
                        append("    ")
                        append(it.descriptor.getElementName(index))
                        append(": ")
                        out.append(sub.write())
                        appendLine()
                    }
                    appendLine("}")
                }
                is SerialKind.ENUM -> {
                    append("export enum ")
                    it.write().let { out.append(it) }
                    appendLine(" {")
                    for(index in 0 until it.descriptor.elementsCount) {
                        append("    ")
                        append(it.descriptor.getElementName(index))
                        append(" = \"")
                        append(it.descriptor.getElementName(index))
                        append("\",")
                        appendLine()
                    }
                    appendLine("}")
                }
                else -> {}
            }
        }

    appendLine()
    appendLine()
    appendLine()

    val byGroup = safeDocumentables.groupBy { it.docGroup }
    val groups = byGroup.keys.filterNotNull()
    appendLine("export interface Api {")
    for (group in groups) {
        appendLine("    readonly ${group.groupToPartName()}: {")
        for (entry in byGroup[group]!!) {
            append("        ")
            append(entry.functionName)
            this.functionHeader(entry)
            appendLine()
        }
        appendLine("    }")
    }
    for (entry in byGroup[null] ?: listOf()) {
        append("    ")
        append(entry.functionName)
        this.functionHeader(entry)
        appendLine()
    }
    appendLine("}")

    appendLine()
    appendLine()
    appendLine()

    val byUserType = safeDocumentables.groupBy { it.authInfo.type }
    val userTypes = byUserType.keys.filterNotNull()
    userTypes.forEach { userType ->
        @Suppress("NAME_SHADOWING") val byGroup = ((byUserType[userType] ?: listOf()) + (byUserType[null] ?: listOf())).groupBy { it.docGroup }
        @Suppress("NAME_SHADOWING") val groups = byGroup.keys.filterNotNull()
        val sessionClassName = "${userType.substringAfterLast('.')}Session"
        appendLine("export class $sessionClassName {")
        appendLine("    constructor(public api: Api, public ${userType.userTypeTokenName()}: string) {}")
        for (entry in byGroup[null] ?: listOf()) {
            append("    ")
            append(entry.functionName)
            this.functionHeader(entry, skipAuth = true)
            append(" { return this.api.")
            functionCall(entry, skipAuth = false, authUsesThis = true, overrideUserType = userType)
            appendLine(" } ")
        }
        for (group in groups) {
            appendLine("    readonly ${group.groupToPartName()} = {")
            for (entry in byGroup[group]!!) {
                append("        ")
                append(entry.functionName)
                append(" = ")
                this.functionHeader(entry, skipAuth = true)
                append(" => { return this.api.")
                append(group.groupToPartName())
                append(".")
                functionCall(entry, skipAuth = false, authUsesThis = true, overrideUserType = userType)
                appendLine(" }, ")
            }
            appendLine("    }")
        }
        appendLine("}")
        appendLine()
    }

    appendLine()
    appendLine()
    appendLine()

    appendLine("export class LiveApi implements Api {")
    appendLine("    public constructor(public httpUrl: string, public socketUrl: string = httpUrl, public extraHeaders: Record<string, string> = {}) {}")
    for (entry in byGroup[null] ?: listOf()) {
        append("    ")
        append(entry.functionName)
        this.functionHeader(entry, skipAuth = false)
        appendLine(" {")
        val hasInput = entry.inputType != Unit.serializer()
        appendLine("        return apiCall(`\${this.httpUrl}${entry.route.path.escaped}`, ${if(hasInput) "input" else "undefined"}, {")
        appendLine("            method: \"${entry.route.method}\",")
        entry.authInfo.type?.let {
            appendLine("            headers: ${it.userTypeTokenName()} ? { ...this.extraHeaders, \"Authorization\": `Bearer \${${it.userTypeTokenName()}}` } : this.extraHeaders,")
        }
        appendLine("            }, ")
        entry.outputType.takeUnless { it == Unit.serializer() }?.let {
            appendLine("        ).then(x => x.json())")
        } ?: run {
            appendLine("        ).then(x => undefined)")
        }
        appendLine("    }")
    }
    for (group in groups) {
        appendLine("    readonly ${group.groupToPartName()} = {")
        for (entry in byGroup[group]!!) {
            append("        ")
            append(entry.functionName)
            append(" = ")
            this.functionHeader(entry, skipAuth = false)
            appendLine(" => {")
            val hasInput = entry.inputType != Unit.serializer()
            appendLine("            return apiCall(`\${this.httpUrl}${entry.route.path.escaped}`, ${if(hasInput) "input" else "undefined"}, {")
            appendLine("                method: \"${entry.route.method}\",")
            entry.authInfo.type?.let {
                appendLine("                headers: ${it.userTypeTokenName()} ? { ...this.extraHeaders, \"Authorization\": `Bearer \${${it.userTypeTokenName()}}` } : this.extraHeaders,")
            }
            appendLine("            }, ")
            entry.outputType.takeUnless { it == Unit.serializer() }?.let {
                appendLine("            ).then(x => x.json())")
            } ?: run {
                appendLine("            ).then(x => undefined)")
            }
            appendLine("        },")
        }
        appendLine("    }")
    }
    appendLine("}")
    appendLine()
}

private val skipSet = setOf(
    "Query",
    "MassModification",
    "EntryChange",
    "ListChange",
    "Modification",
    "Condition",
    "GroupCountQuery",
    "AggregateQuery",
    "GroupAggregateQuery",
    "Aggregate",
)
private fun String.groupToInterfaceName(): String = replaceFirstChar { it.uppercase() } + "Api"
private fun String.groupToPartName(): String = replaceFirstChar { it.lowercase() }
@Suppress("UNCHECKED_CAST")
private fun KType?.userTypeTokenName(): String = (this?.classifier as? KClass<Any>)?.userTypeTokenName() ?: "token"
private fun KClass<*>.userTypeTokenName(): String =
    simpleName?.replaceFirstChar { it.lowercase() }?.plus("Token") ?: "token"

private fun Appendable.functionHeader(documentable: Documentable, skipAuth: Boolean = false, overrideUserType: String? = null) {
    append("(")
    var argComma = false
    arguments(documentable, skipAuth, overrideUserType).forEach {
        if (argComma) append(", ")
        else argComma = true
        append(it.name)
        if(it.optional) append("?")
        append(": ")
        it.type?.write()?.let { append(it) } ?: it.stringType?.let { append(it) }
    }
    append("): ")
    when (documentable) {
        is ApiEndpoint<*, *, *> -> {
            append("Promise<")
            documentable.outputType.write().let { append(it) }
            append(">")
        }
        is ApiWebsocket<*, *, *> -> {
            append("Observable<WebSocketIsh<")
            documentable.inputType.write().let { append(it) }
            append(", ")
            documentable.outputType.write().let { append(it) }
            append(">>")
        }
        else -> TODO()
    }
}

private fun Appendable.functionCall(documentable: Documentable, skipAuth: Boolean = false, authUsesThis: Boolean = false, overrideUserType: String? = null) {
    append("${documentable.functionName}(")
    var argComma = false
    arguments(documentable, skipAuth, overrideUserType).forEach {
        if (argComma) append(", ")
        else argComma = true
        if(it.name == documentable.authInfo.type?.userTypeTokenName() && authUsesThis) {
            append("this.")
        }
        append(it.name)
    }
    append(")")
}

private data class TArg(
    val name: String,
    val type: KSerializer<*>? = null,
    val stringType: String? = null,
    val default: String? = null,
    val optional: Boolean = false
)

private fun arguments(documentable: Documentable, skipAuth: Boolean = false, overrideUserType: String? = null): List<TArg> = when (documentable) {
    is ApiEndpoint<*, *, *> -> listOfNotNull(
        documentable.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>()
            .map {
                TArg(name = it.name, type = documentable.routeTypes[it.name], stringType = "string")
            },
        documentable.inputType.takeUnless { it == Unit.serializer() }?.let {
            TArg(name = "input", type = it)
        }?.let(::listOf),
        documentable.authInfo.type?.takeUnless { skipAuth }?.let {
            TArg(name = (overrideUserType ?: it).userTypeTokenName(), stringType = "string", optional = !documentable.authInfo.required)
        }?.let(::listOf)
    ).flatten()
    is ApiWebsocket<*, *, *> -> listOfNotNull(
        documentable.authInfo.type?.takeUnless { skipAuth }?.let {
            TArg(name = (overrideUserType ?: it).userTypeTokenName(), stringType = "string", optional = !documentable.authInfo.required)
        }?.let(::listOf),
        documentable.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>()
            .map {
                TArg(name = it.name, stringType = "string")
            }
    ).flatten()
    else -> TODO()
}


private fun KSerializer<*>.write(): String = if(this == Unit.serializer()) "void" else StringBuilder().also { out ->
    when (descriptor.kind) {
        PrimitiveKind.BOOLEAN -> out.append("boolean")
        PrimitiveKind.BYTE,
        PrimitiveKind.SHORT,
        PrimitiveKind.INT,
        PrimitiveKind.LONG,
        PrimitiveKind.FLOAT,
        PrimitiveKind.DOUBLE -> out.append("number")
        PrimitiveKind.CHAR,
        PrimitiveKind.STRING -> out.append("string")
        StructureKind.LIST -> {
            out.append("Array<${this.listElement()!!.write()}>")
        }
        StructureKind.MAP -> {
            out.append("Record")
            listOf("string", this.mapValueElement()!!.write()).joinToString(", ", "<", ">").let {
                out.append(it)
            }
        }
        SerialKind.CONTEXTUAL -> {
            this.uncontextualize().write().let { out.append(it) }
        }
        is PolymorphicKind,
        StructureKind.OBJECT,
        SerialKind.ENUM,
        StructureKind.CLASS -> {
            out.append(descriptor.serialName.substringBefore('<').substringAfterLast('.').removeSuffix("?"))
            this.subSerializers().takeUnless { it.isEmpty() }?.joinToString(", ", "<", ">") { it.write() }?.let {
                out.append(it)
            }
        }
    }
    if(descriptor.isNullable) out.append(" | null | undefined")
}.toString()

private fun String.userTypeTokenName(): String = this.substringAfterLast('.').replaceFirstChar { it.lowercase() }.plus("Token")