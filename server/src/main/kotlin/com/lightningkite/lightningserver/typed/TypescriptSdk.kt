package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningdb.nullElement
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.Http
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.websocket.WebSockets
import io.ktor.http.*
import io.ktor.server.routing.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.serializer
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.KType

fun Documentable.Companion.typescriptSdk(out: Appendable) = with(out) {
    val safeDocumentables = endpoints.filter { it.inputType == Unit.serializer() || it.route.method != HttpMethod.GET }.toList()
    appendLine("import { ${skipSet.joinToString()} } from '@lightningkite/ktor-batteries-simplified'")
    appendLine()
    usedTypes
        .sortedBy { it.descriptor.serialName.substringBefore('<').substringAfterLast('.') }
        .map { it.descriptor }
        .forEach {
            when(it.kind) {
                is StructureKind.CLASS -> {
                    append("export interface ")
                    it.write(out)
                    appendLine(" {")
                    for(index in 0 until it.elementsCount) {
                        append("    ")
                        append(it.getElementName(index))
                        append(": ")
                        it.getElementDescriptor(index).write(out)
                        appendLine()
                    }
                    appendLine("}")
                }
                is SerialKind.ENUM -> {
                    append("export enum ")
                    it.write(out)
                    appendLine(" {")
                    for(index in 0 until it.elementsCount) {
                        append("    ")
                        append(it.getElementName(index))
                        append(" = \"")
                        it.getElementDescriptor(index).write(out)
                        append("\",")
                        appendLine()
                    }
                    appendLine("}")
                }
            }
        }
    println("Types done")

    appendLine()
    appendLine()
    appendLine()

    println("gathering groups: $safeDocumentables")
    val byGroup = safeDocumentables.groupBy { it.docGroup }
    println("groups built $byGroup")
    val groups = byGroup.keys.filterNotNull()
    println("groups $groups")
    appendLine("export interface Api {")
    for (group in groups) {
        println("Handling group $group")
        appendLine("    readonly ${group.groupToPartName()}: {")
        for (entry in byGroup[group]!!) {
            append("        ")
            this.functionHeader(entry)
            appendLine()
        }
        appendLine("    }")
    }
    println("Handling null group")
    for (entry in byGroup[null] ?: listOf()) {
        append("    ")
        this.functionHeader(entry)
        appendLine()
    }
    appendLine("}")
    println("API done")

    appendLine()
    appendLine()
    appendLine()

    val byUserType = safeDocumentables.groupBy { it.authInfo.type }
    val userTypes = byUserType.keys.filterNotNull()
    userTypes.forEach { userType ->
        val byGroup = ((byUserType[userType] ?: listOf()) + (byUserType[null] ?: listOf())).groupBy { it.docGroup }
        val groups = byGroup.keys.filterNotNull()
        val sessionClassName = "${userType.substringAfterLast('.')}Session"
        appendLine("export class $sessionClassName {")
        appendLine("    constructor(public api: Api, public ${userType.userTypeTokenName()}: string) {}")
        for (entry in byGroup[null] ?: listOf()) {
            append("    ")
            this.functionHeader(entry, skipAuth = true)
            append(" { return this.api.")
            functionCall(entry, skipAuth = false, authUsesThis = true, overrideUserType = userType)
            appendLine(" } ")
        }
        for (group in groups) {
            appendLine("    readonly ${group.groupToPartName()} = {")
            appendLine("        api: this.api,")
            appendLine("        ${userType.userTypeTokenName()}: this.${userType.userTypeTokenName()},")
            for (entry in byGroup[group]!!) {
                append("        ")
                this.functionHeader(entry, skipAuth = true)
                append(" { return this.api.")
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
    println("Sessions done")

    appendLine()
    appendLine()
    appendLine()

    appendLine("export class LiveApi implements Api {")
    appendLine("    public constructor(public httpUrl: String, public socketUrl: String = httpUrl) {}")
    for (group in groups) {
        appendLine("    readonly ${group.groupToPartName()} = {")
        appendLine("        httpUrl: this.httpUrl,")
        appendLine("        socketUrl: this.socketUrl,")
        for (entry in byGroup[group]!!) {
            append("        ")
            this.functionHeader(entry, skipAuth = false)
            appendLine(" {")
            appendLine("            return fetch(`\${this.httpUrl}${entry.route.path.escaped}`, {")
            appendLine("                method: \"${entry.route.method}\",")
            entry.authInfo.type?.let {
                appendLine("                headers: { \"Authorization\": `Bearer \${${entry.authInfo.type.userTypeTokenName()}}` },")
            }
            entry.inputType.takeUnless { it == Unit.serializer() }?.let {
                appendLine("                body: JSON.stringify(input)")
            }
            entry.outputType.takeUnless { it == Unit.serializer() }?.let {
                appendLine("            }).then(x => x.json())")
            } ?: run {
                appendLine("            }).then(x => undefined)")
            }
            appendLine("        },")
        }
        appendLine("    }")
    }
    for (entry in byGroup[null] ?: listOf()) {
        append("    ")
        this.functionHeader(entry, skipAuth = false)
        appendLine(" {")
        appendLine("        return fetch(`\${this.httpUrl}${entry.route.path.escaped}`, {")
        appendLine("            method: \"${entry.route.method}\",")
        entry.authInfo.type?.let {
            appendLine("            headers: { \"Authorization\": `Bearer \${${entry.authInfo.type.userTypeTokenName()}}` },")
        }
        entry.inputType.takeUnless { it == Unit.serializer() }?.let {
            appendLine("            body: JSON.stringify(input)")
        }
        entry.outputType.takeUnless { it == Unit.serializer() }?.let {
            appendLine("        }).then(x => x.json())")
        } ?: run {
            appendLine("        }).then(x => undefined)")
        }
        appendLine("    }")
    }
    appendLine("}")
    println("Live done")
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
private fun KType?.userTypeTokenName(): String = (this?.classifier as? KClass<Any>)?.userTypeTokenName() ?: "token"
private fun KClass<*>.userTypeTokenName(): String =
    simpleName?.replaceFirstChar { it.lowercase() }?.plus("Token") ?: "token"

private fun Appendable.functionHeader(documentable: Documentable, skipAuth: Boolean = false, overrideUserType: String? = null) {
    append("${documentable.functionName}(")
    var argComma = false
    arguments(documentable, skipAuth, overrideUserType).forEach {
        if (argComma) append(", ")
        else argComma = true
        append(it.name)
        append(": ")
        it.type?.write(this) ?: it.stringType?.let { append(it) }
    }
    append("): ")
    when (documentable) {
        is ApiEndpoint<*, *, *> -> {
            append("Promise<")
            documentable.outputType.write(this)
            append(">")
        }
        is ApiWebsocket<*, *, *> -> {
            append("Observable<WebSocketIsh<")
            documentable.inputType.write(this)
            append(", ")
            documentable.outputType.write(this)
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
    val default: String? = null
)

private fun arguments(documentable: Documentable, skipAuth: Boolean = false, overrideUserType: String? = null): List<TArg> = when (documentable) {
    is ApiEndpoint<*, *, *> -> listOfNotNull(
        documentable.authInfo.type?.takeUnless { skipAuth }?.let {
            TArg(name = (overrideUserType ?: it).userTypeTokenName(), stringType = "string")
        }?.let(::listOf),
        documentable.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>()
            .map {
                TArg(name = it.name, type = documentable.routeTypes[it.name], stringType = "string")
            },
        documentable.inputType.takeUnless { it == Unit.serializer() }?.let {
            TArg(name = "input", type = it)
        }?.let(::listOf)
    ).flatten()
    is ApiWebsocket<*, *, *> -> listOfNotNull(
        documentable.authInfo.type?.takeUnless { skipAuth }?.let {
            TArg(name = (overrideUserType ?: it).userTypeTokenName(), stringType = "String")
        }?.let(::listOf),
        documentable.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>()
            .map {
                TArg(name = it.name, stringType = "string")
            }
    ).flatten()
    else -> TODO()
}


private fun KSerializer<*>.write(out: Appendable): Unit = descriptor.write(out)
private fun SerialDescriptor.write(out: Appendable): Unit {
    if(this == Unit.serializer().descriptor) {
        out.append("void")
        return
    }
    when (kind) {
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
            out.append("Array<")
            this.getElementDescriptor(0).write(out)
            out.append(">")
        }
        StructureKind.MAP -> {
            out.append("Record<")
            this.getElementDescriptor(0).write(out)
            out.append(", ")
            this.getElementDescriptor(1).write(out)
            out.append(">")
        }
        SerialKind.CONTEXTUAL -> {
            Serialization.json.serializersModule.getContextualDescriptor(this)?.write(out) ?: out.append(this.serialName.substringAfterLast('.'))
        }
        is PolymorphicKind,
        StructureKind.OBJECT,
        SerialKind.ENUM,
        StructureKind.CLASS -> {
            out.append(serialName.substringBefore('<').substringAfterLast('.').removeSuffix("?"))
            serialName.substringAfter('<', "").takeUnless { it.isEmpty() }
                ?.removeSuffix(">")
                ?.split(",")
                ?.map { it.trim() }
                ?.map { it.substringAfterLast('.') }
                ?.joinToString(", ", "<", ">")
                ?.let { out.append(it.replace("?", " | null | undefined")) }
        }
    }
    if(this.isNullable) out.append(" | null | undefined")
}

private fun String.userTypeTokenName(): String = this.substringAfterLast('.').replaceFirstChar { it.lowercase() }.plus("Token")