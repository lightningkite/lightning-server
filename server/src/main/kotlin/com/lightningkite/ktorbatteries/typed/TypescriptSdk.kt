package com.lightningkite.ktorbatteries.typed

import com.lightningkite.ktorbatteries.routes.fullPath
import com.lightningkite.ktorbatteries.routes.maybeMethod
import com.lightningkite.ktorbatteries.serialization.Serialization
import com.lightningkite.ktordb.nullElement
import io.ktor.http.*
import io.ktor.server.routing.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.serializer
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.KType

object TypescriptSdk {
    fun make(sourceRoot: File, packageName: String) {
        val fileFolder = sourceRoot.resolve(packageName.replace('.', '/'))
        fileFolder.mkdirs()
        fileFolder.resolve("sdk.ts").printWriter().use { sdkFile(it) }
    }

    private val safeDocumentables =
        (ApiEndpoint.known.filter { it.route.selector.maybeMethod != HttpMethod.Get || it.inputType == null })
            .distinctBy { it.docGroup.toString() + "/" + it.summary }

    private val skipSet = setOf(
        "Query",
        "MassModification",
        "EntryChange",
        "ListChange",
        "Modification",
        "Condition"
    )

    fun sdkFile(out: Appendable) = with(out) {
        appendLine("import { ${skipSet.joinToString()} } from '@lightningkite/ktor-batteries-simplified'")
        appendLine()
        val seen: HashSet<SerialDescriptor> = HashSet()
        fun onAllTypes(at: SerialDescriptor, action: (SerialDescriptor)->Unit) {
            if(!seen.add(at)) return
            val real = if(at.kind == SerialKind.CONTEXTUAL)
                Serialization.json.serializersModule.getContextualDescriptor(at)!!
            else if(at.isNullable)
                at.nullElement()!!
            else
                at
            if(real.serialName.startsWith("com.lightningkite.ktordb") || real.serialName in skipSet) return
            action(real)
            real.elementDescriptors.forEach { onAllTypes(it, action) }
        }
        val types = HashSet<SerialDescriptor>()
        safeDocumentables.asSequence().flatMap {
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
                this.functionHeader(entry)
                appendLine()
            }
            appendLine("    }")
        }
        for (entry in byGroup[null] ?: listOf()) {
            append("    ")
            this.functionHeader(entry)
            appendLine()
        }
        appendLine("}")

        appendLine()
        appendLine()
        appendLine()

        val byUserType = safeDocumentables.groupBy { it.userType }
        val userTypes = byUserType.keys.filterNotNull()
        userTypes.forEach { userType ->
            val byGroup = ((byUserType[userType] ?: listOf()) + (byUserType[null] ?: listOf())).groupBy { it.docGroup }
            val groups = byGroup.keys.filterNotNull()
            val sessionClassName = "${(userType.classifier as KClass<*>).simpleName}Session"
            appendLine("export class $sessionClassName {")
            appendLine("    constructor(public api: Api, public ${userType.userTypeTokenName()}: string) {}")
            for (entry in byGroup[null] ?: listOf()) {
                append("    ")
                this.functionHeader(entry, skipAuth = true, overrideUserType = userType)
                append(" { return this.api.")
                functionCall(entry, skipAuth = false, authUsesThis = true, overrideUserType = userType)
                appendLine(" } ")
            }
            for (group in groups) {
                appendLine("    readonly ${group.groupToPartName()} = {")
                appendLine("        api: this.api,")
                appendLine("        userToken: this.userToken,")
                for (entry in byGroup[group]!!) {
                    append("        ")
                    this.functionHeader(entry, skipAuth = true, overrideUserType = userType)
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
                appendLine("            return fetch(`\${this.httpUrl}${entry.route.fullPath}`, {")
                appendLine("                method: \"${entry.route.selector.maybeMethod?.value?.uppercase() ?: "GET"}\",")
                entry.userType?.let {
                    appendLine("                headers: { \"Authorization\": `Bearer \${${entry.userType.userTypeTokenName()}}` },")
                }
                entry.inputType?.let {
                    appendLine("                body: JSON.stringify(input)")
                }
                entry.outputType?.let {
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
            appendLine("        return fetch(`\${this.httpUrl}${entry.route.fullPath}`, {")
            appendLine("            method: \"${entry.route.selector.maybeMethod?.value?.uppercase() ?: "GET"}\",")
            entry.userType?.let {
                appendLine("            headers: { \"Authorization\": `Bearer \${${entry.userType.userTypeTokenName()}}` },")
            }
            entry.inputType?.let {
                appendLine("            body: JSON.stringify(input)")
            }
            entry.outputType?.let {
                appendLine("        }).then(x => x.json())")
            } ?: run {
                appendLine("        }).then(x => undefined)")
            }
            appendLine("    }")
        }
        appendLine("}")
        appendLine()
    }
}

private fun String.groupToInterfaceName(): String = replaceFirstChar { it.uppercase() } + "Api"
private fun String.groupToPartName(): String = replaceFirstChar { it.lowercase() }
private fun KType?.userTypeTokenName(): String = (this?.classifier as? KClass<Any>)?.userTypeTokenName() ?: "token"
private fun KClass<*>.userTypeTokenName(): String =
    simpleName?.replaceFirstChar { it.lowercase() }?.plus("Token") ?: "token"

private fun Appendable.functionHeader(documentable: Documentable, skipAuth: Boolean = false, overrideUserType: KType? = null) {
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
            documentable.outputType?.let {
                append("Promise<")
                it.write(this)
                append(">")
            } ?: append("Promise<void>")
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

private fun Appendable.functionCall(documentable: Documentable, skipAuth: Boolean = false, authUsesThis: Boolean = false, overrideUserType: KType? = null) {
    append("${documentable.functionName}(")
    var argComma = false
    arguments(documentable, skipAuth, overrideUserType).forEach {
        if (argComma) append(", ")
        else argComma = true
        if(it.name == documentable.userType.userTypeTokenName() && authUsesThis) {
            append("this.")
        }
        append(it.name)
    }
    append(")")
}

private data class TArg(
    val name: String,
    val type: KType? = null,
    val stringType: String? = null,
    val default: String? = null
)

private fun arguments(documentable: Documentable, skipAuth: Boolean = false, overrideUserType: KType? = null): List<TArg> = when (documentable) {
    is ApiEndpoint<*, *, *> -> listOfNotNull(
        documentable.userType?.takeUnless { skipAuth }?.let {
            TArg(name = (overrideUserType ?: it).userTypeTokenName(), stringType = "string")
        }?.let(::listOf),
        generateSequence(documentable.route) { it.parent }.toList().reversed()
            .mapNotNull { it.selector as? PathSegmentParameterRouteSelector }
            .map {
                TArg(name = it.name, type = documentable.routeTypes[it.name], stringType = "string")
            },
        documentable.inputType?.let {
            TArg(name = "input", type = it)
        }?.let(::listOf)
    ).flatten()
    is ApiWebsocket<*, *, *> -> listOfNotNull(
        documentable.userType?.takeUnless { skipAuth }?.let {
            TArg(name = (overrideUserType ?: it).userTypeTokenName(), stringType = "String")
        }?.let(::listOf),
        generateSequence(documentable.route) { it.parent }.toList().reversed()
            .mapNotNull { it.selector as? PathSegmentParameterRouteSelector }
            .map {
                TArg(name = it.name, stringType = "string")
            }
    ).flatten()
    else -> TODO()
}


private fun KType.write(out: Appendable): Unit {
    val desc = Serialization.json.serializersModule.serializer(this).descriptor
    if(desc.kind == StructureKind.CLASS) {
        val dividers = Regex("[,<>]")
//        out.append("/*${this}*/")
        this.toString().split(dividers).asSequence().plus("")
            .map {
                if(it.endsWith("?")) it.removeSuffix("?") + " | null | undefined"
                else it
            }
            .zip(dividers.findAll(this.toString()).map { it.value }.plus(""))
            .joinToString("") { it.first.substringAfterLast('.') + it.second }
            .let { out.append(it) }
    } else {
        desc.write(out)
    }
}


private fun SerialDescriptor.write(out: Appendable): Unit {
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
