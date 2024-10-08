package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningdb.*
import com.lightningkite.serialization.*
import com.lightningkite.serialization.*
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.HttpMethod
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlin.reflect.KClass
import kotlin.reflect.KType

@OptIn(InternalSerializationApi::class)
fun Documentable.Companion.typescriptSdk(out: Appendable) = with(out) {
    val safeDocumentables =
        endpoints.filter { it.inputType == Unit.serializer() || it.route.method != HttpMethod.GET }.toList()
    appendLine("import { ${fromLightningServerPackage.joinToString()}, apiCall, Path, DeepPartial } from '@lightningkite/lightning-server-simplified'")
    appendLine()
    usedTypes
        .filter { it.descriptor.simpleSerialName !in skipFromLsPackage }
        .sortedBy { it.descriptor.simpleSerialName }
        .forEach {
            when (it.descriptor.kind) {
                is StructureKind.CLASS -> {
                    if (it is MySealedClassSerializer) return@forEach
                    emitTypeComment(it)
                    append("export interface ")
                    it.write().let { out.append(it) }
                    appendLine(" {")
                    (it.serializableProperties?.map { it.serializer } ?: it.tryChildSerializers()?.toList()
                    ?: listOf()).forEachIndexed { index, sub ->
                        append("    ")
                        append(it.descriptor.getElementName(index))
                        append(": ")
                        out.append(sub.write())
                        appendLine()
                    }
                    appendLine("}")
                }

                is SerialKind.ENUM -> {
                    emitTypeComment(it)
                    append("export enum ")
                    it.write().let { out.append(it) }
                    appendLine(" {")
                    for (index in 0 until it.descriptor.elementsCount) {
                        append("    ")
                        it.descriptor.getElementName(index).first()
                            .let { if (it.isJavaIdentifierStart()) it else '_' }.let { append(it) }
                        it.descriptor.getElementName(index).drop(1)
                            .map { if (it.isJavaIdentifierPart()) it else '_' }.forEach { append(it) }
                        append(" = \"")
                        append(it.descriptor.getElementName(index))
                        append("\",")
                        appendLine()
                    }
                    appendLine("}")
                }

                is PrimitiveKind.STRING -> {
                    if (it.descriptor.simpleSerialName != "String") {
                        emitTypeComment(it)
                        appendLine("type ${it.descriptor.simpleSerialName} = string  // ${it.descriptor.serialName}")
                    }
                }

                else -> {}
            }
        }

    appendLine()
    appendLine()
    appendLine()

    val byGroup = safeDocumentables.groupBy { it.docGroupIdentifier }
    val groups = byGroup.keys.filterNotNull().sortedBy { it.groupToPartName() }
    appendLine("export interface Api {")
    for (entry in byGroup[null]?.sortedBy { it.functionName } ?: listOf()) {
        appendLine("    ")
        appendLine("     /**")
        entry.description.split('\n').map { it.trim() }.forEach {
            appendLine("     * $it")
        }
        appendLine("     **/")
        append("    ")
        append(entry.functionName)
        this.functionHeader(entry)
        appendLine()
    }
    for (group in groups) {
        appendLine("    readonly ${group.groupToPartName()}: {")
        for (entry in byGroup[group]!!) {
            appendLine("        ")
            appendLine("        /**")
            entry.description.split('\n').map { it.trim() }.forEach {
                appendLine("        * $it")
            }
            appendLine("        **/")
            append("        ")
            append(entry.functionName)
            this.functionHeader(entry)
            appendLine()
        }
        appendLine("    }")
    }
    appendLine("}")

    appendLine()
    appendLine()
    appendLine()

    val byUserType = safeDocumentables.groupBy { it.primaryAuthName }
    val userTypes = byUserType.keys.filterNotNull()
    userTypes.forEach { userType ->
        @Suppress("NAME_SHADOWING") val byGroup =
            ((byUserType[userType] ?: listOf()) + (byUserType[null] ?: listOf())).groupBy { it.docGroupIdentifier }
        @Suppress("NAME_SHADOWING") val groups = byGroup.keys.filterNotNull().sortedBy { it.groupToPartName() }
        val sessionClassName = "${userType.substringAfterLast('.')}Session"
        appendLine("export class $sessionClassName {")
        appendLine("    constructor(public api: Api, public ${userType.userTypeTokenName()}: ()=>string) {}")
        for (entry in byGroup[null]?.sortedBy { it.functionName } ?: listOf()) {
            appendLine("    ")
            appendLine("    /**")
            entry.description.split('\n').map { it.trim() }.forEach {
                appendLine("    * $it")
            }
            appendLine("    **/")
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
                appendLine("        ")
                appendLine("        /**")
                entry.description.split('\n').map { it.trim() }.forEach {
                    appendLine("        * $it")
                }
                appendLine("        **/")
                append("        ")
                append(entry.functionName)
                append(": ")
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
    appendLine("    public constructor(public httpUrl: string, public socketUrl: string = httpUrl, public extraHeaders: Record<string, string> = {}, public responseInterceptors?: (x: Response)=>Response) {}")
    for (entry in byGroup[null]?.sortedBy { it.functionName } ?: listOf()) {
        appendLine("    ")
        appendLine("    /**")
        entry.description.split('\n').map { it.trim() }.forEach {
            appendLine("    * $it")
        }
        appendLine("    **/")
        append("    ")
        append(entry.functionName)
        this.functionHeader(entry, skipAuth = false)
        appendLine(" {")
        val hasInput = entry.inputType != Unit.serializer()
        appendLine("        return apiCall<${entry.inputType.write()}>(")
        appendLine("            `\${this.httpUrl}${entry.path.path.escaped}`,")
        appendLine("            ${if (hasInput) "input" else "undefined"},")
        appendLine("            {")
        appendLine("                method: \"${entry.route.method}\",")
        entry.primaryAuthName?.let {
            appendLine("                headers: ${it.userTypeTokenName()} ? { ...this.extraHeaders, \"Authorization\": `Bearer \${${it.userTypeTokenName()}}` } : this.extraHeaders,")
        }
        appendLine("            }, ")
        appendLine("            undefined,")
        appendLine("            this.responseInterceptors, ")
        entry.outputType.takeUnless { it == Unit.serializer() }?.let {
            appendLine("        ).then(x => x.json())")
        } ?: run {
            appendLine("        ).then(x => undefined)")
        }
        appendLine("    }")
    }
    for (group in groups.sortedBy { it.groupToPartName() }) {
        appendLine("    readonly ${group.groupToPartName()} = {")
        for (entry in byGroup[group]!!) {
            appendLine("        ")
            appendLine("        /**")
            entry.description.split('\n').map { it.trim() }.forEach {
                appendLine("        * $it")
            }
            appendLine("        **/")
            append("        ")
            append(entry.functionName)
            append(": ")
            this.functionHeader(entry, skipAuth = false)
            appendLine(" => {")
            val hasInput = entry.inputType != Unit.serializer()
            appendLine("            return apiCall<${entry.inputType.write()}>(")
            appendLine("                `\${this.httpUrl}${entry.path.path.escaped}`,")
            appendLine("                ${if (hasInput) "input" else "undefined"},")
            appendLine("                {")
            appendLine("                    method: \"${entry.route.method}\",")
            entry.primaryAuthName?.let {
                appendLine("                    headers: ${it.userTypeTokenName()} ? { ...this.extraHeaders, \"Authorization\": `Bearer \${${it.userTypeTokenName()}}` } : this.extraHeaders,")
            }
            appendLine("                }, ")
            appendLine("                undefined,")
            appendLine("                this.responseInterceptors, ")
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

private fun Appendable.emitTypeComment(it: KSerializer<*>) {
    appendLine()
    it.descriptor.annotations
        .filterIsInstance<Description>()
        .takeUnless { it.isEmpty() }
        ?.let {
            appendLine("/**")
            it.joinToString("\n") { it.text }.split("\n").forEach {
                appendLine("* $it")
            }
            appendLine("**/")
        }
}

private val fromLightningServerPackage = setOf(
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
    "SortPart",
    "DataClassPath",
    "DataClassPathPartial",
    "QueryPartial",
)
private val skipFromLsPackage = setOf(
    "Partial",
) + fromLightningServerPackage

private fun String.groupToInterfaceName(): String = replaceFirstChar { it.uppercase() } + "Api"
private fun String.groupToPartName(): String = replaceFirstChar { it.lowercase() }

@Suppress("UNCHECKED_CAST")
private fun KType?.userTypeTokenName(): String = (this?.classifier as? KClass<Any>)?.userTypeTokenName() ?: "token"
private fun KClass<*>.userTypeTokenName(): String =
    simpleName?.replaceFirstChar { it.lowercase() }?.plus("Token") ?: "token"

private fun Appendable.functionHeader(
    documentable: Documentable,
    skipAuth: Boolean = false,
    overrideUserType: String? = null
) {
    append("(")
    var argComma = false
    arguments(documentable, skipAuth, overrideUserType).forEach {
        if (argComma) append(", ")
        else argComma = true
        append(it.name)
        if (it.optional) append("?")
        append(": ")
        it.type?.write()?.let { append(it) } ?: it.stringType?.let { append(it) }
    }
    append("): ")
    when (documentable) {
        is ApiEndpoint<*, *, *, *> -> {
            append("Promise<")
            documentable.outputType.write().let { append(it) }
            append(">")
        }

        is ApiWebsocket<*, *, *, *> -> {
            append("Observable<WebSocketIsh<")
            documentable.inputType.write().let { append(it) }
            append(", ")
            documentable.outputType.write().let { append(it) }
            append(">>")
        }

        else -> TODO()
    }
}

private fun Appendable.functionCall(
    documentable: Documentable,
    skipAuth: Boolean = false,
    authUsesThis: Boolean = false,
    overrideUserType: String? = null
) {
    append("${documentable.functionName}(")
    var argComma = false
    arguments(documentable, skipAuth, overrideUserType).forEach {
        if (argComma) append(", ")
        else argComma = true
        if (it.name == documentable.primaryAuthName?.userTypeTokenName() && authUsesThis) {
            append("this.${it.name}()")
        } else {
            append(it.name)
        }
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

private fun arguments(
    documentable: Documentable,
    skipAuth: Boolean = false,
    overrideUserType: String? = null
): List<TArg> = when (documentable) {
    is ApiEndpoint<*, *, *, *> -> listOfNotNull(
        documentable.path.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>()
            .mapIndexed { index, it ->
                TArg(name = it.name, type = documentable.path.serializers[index], stringType = "String")
            },
        documentable.inputType.takeUnless { it == Unit.serializer() }?.let {
            TArg(name = "input", type = it)
        }?.let(::listOf),
        documentable.primaryAuthName?.takeUnless { skipAuth }?.let {
            TArg(
                name = (overrideUserType ?: it).userTypeTokenName(),
                stringType = "string",
                optional = !documentable.authOptions.options.contains(null).not()
            )
        }?.let(::listOf)
    ).flatten()

    is ApiWebsocket<*, *, *, *> -> listOfNotNull(
        documentable.primaryAuthName?.takeUnless { skipAuth }?.let {
            TArg(
                name = (overrideUserType ?: it).userTypeTokenName(),
                stringType = "string",
                optional = !documentable.authOptions.options.contains(null).not()
            )
        }?.let(::listOf),
        documentable.path.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>()
            .map {
                TArg(name = it.name, stringType = "string")
            }
    ).flatten()

    else -> TODO()
}


private fun KSerializer<*>.write(): String = nullElement()?.let { it.write() + " | null | undefined" } ?: when {
    this == Unit.serializer() -> "void"
    else -> StringBuilder().also { out ->
        when (descriptor.kind) {
            PrimitiveKind.BOOLEAN -> out.append("boolean")
            PrimitiveKind.BYTE,
            PrimitiveKind.SHORT,
            PrimitiveKind.INT,
            PrimitiveKind.LONG,
            PrimitiveKind.FLOAT,
            PrimitiveKind.DOUBLE -> out.append("number")

            PrimitiveKind.CHAR,
            PrimitiveKind.STRING -> {
                val cleanName = this.descriptor.simpleSerialName
                if (cleanName != "String") {
                    out.append(cleanName)
                    this.subSerializers().takeUnless { it.isEmpty() }?.joinToString(", ", "<", ">") { it.write() }
                        ?.let {
                            out.append(it)
                        }
                } else {
                    out.append("string")
                }
            }

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
                if (descriptor.serialName == "com.lightningkite.serialization.Partial") {
                    out.append("DeepPartial")
                } else {
                    out.append(descriptor.simpleSerialName)
                }
                this.tryTypeParameterSerializers2()?.takeUnless { it.isEmpty() }
                    ?.joinToString(", ", "<", ">") { it.write() }?.let {
                        out.append(it)
                    }
            }
        }
    }.toString()
}

private val SerialDescriptor.simpleSerialName: String
    get() = serialName.substringBefore('<').substringAfterLast('.').removeSuffix("?")

private fun String.userTypeTokenName(): String =
    this.substringAfterLast('.').replaceFirstChar { it.lowercase() }.plus("Token")


private val ServerPath.escaped: String
    get() = "/" + segments.joinToString("/") {
        when (it) {
            is ServerPath.Segment.Constant -> it.value
            is ServerPath.Segment.Wildcard -> "\${${it.name}}"
        }
    } + when (after) {
        ServerPath.Afterwards.None -> ""
        ServerPath.Afterwards.TrailingSlash -> "/"
        ServerPath.Afterwards.ChainedWildcard -> "/*"
    }