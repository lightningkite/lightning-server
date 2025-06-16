package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningdb.MySealedClassSerializer
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.typed.Documentable.Companion.endpoints
import com.lightningkite.lightningserver.typed.Documentable.Companion.usedTypes
import com.lightningkite.serialization.listElement
import com.lightningkite.serialization.mapValueElement
import com.lightningkite.serialization.nullElement
import com.lightningkite.serialization.serializableProperties
import com.lightningkite.serialization.tryChildSerializers
import com.lightningkite.serialization.tryTypeParameterSerializers2
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind


@OptIn(InternalSerializationApi::class)
fun Documentable.Companion.typescriptSdk2(out: Appendable) = with(out) {
    val safeDocumentables =
        endpoints.filter { it.inputType == Unit.serializer() || it.route.method != HttpMethod.GET }.toList()
    appendLine("import type { ${fromLightningServerPackage.joinToString()} } from '@lightningkite/lightning-server-simplified'")
    appendLine()

    val stringSerialNames: MutableSet<String> = mutableSetOf()

    usedTypes
        .filter { it.descriptor.simpleSerialName !in skipFromLsPackage }
        .sortedBy { it.descriptor.simpleSerialName }
        .distinctBy { it.write() }
        .forEach {
            when (it.descriptor.kind) {
                is StructureKind.CLASS -> {
                    if (it is MySealedClassSerializer) return@forEach

                    val genericMap: Map<String, String> = it.getGenerics()
                        ?.withIndex()
                        ?.associate { (index, value) ->
                            value.write() to "T${if (index > 0) index else ""}"
                        } ?: emptyMap()


                    fun String.replaceGenerics(): String =
                        genericMap.entries.fold(this) { acc, (old, new) -> acc.replace(old, new) }

                    appendLine("export interface ${it.write().replaceGenerics()} {")
                    (it.serializableProperties?.map { it.serializer } ?: it.tryChildSerializers()?.toList()
                    ?: listOf()).forEachIndexed { index, sub ->
                        appendLine("    ${it.descriptor.getElementName(index)}: ${sub.write().replaceGenerics()}")
                    }
                    appendLine("}")
                }

                is SerialKind.ENUM -> {
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
                    val simpleSerialName = it.descriptor.simpleSerialName
                    if (simpleSerialName != "String" && !stringSerialNames.contains(simpleSerialName)) {
                        stringSerialNames.add(simpleSerialName)
                        appendLine(
                            "export type $simpleSerialName = string  // ${it.descriptor.serialName}".replace(
                                "/loose",
                                ""
                            )
                        )
                    }
                }

                else -> {}
            }
            appendLine()
        }

    appendLine()
    appendLine()


    val byGroup = safeDocumentables.groupBy { it.docGroupIdentifier }
    val groups = byGroup.keys.filterNotNull().sortedBy { it.groupToPartName() }
    appendLine("export interface Api {")
    for (entry in byGroup[null]?.sortedBy { it.functionName } ?: listOf()) {
        append("    ")
        append(entry.functionName)
        this.functionHeader(entry)
        appendLine()
    }
    appendLine()
    for (group in groups) {
        val groupEndpoints = byGroup[group] ?: continue

        appendLine("    readonly ${group.groupToPartName()}: {")
        for (entry in groupEndpoints) {
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


    appendLine("export class LiveApi implements Api {")
    appendLine("    public constructor(public fetcher: Fetcher) {}")

    for (entry in byGroup[null]?.sortedBy { it.functionName } ?: listOf()) {
        append("    ")
        append(entry.functionName)
        this.functionHeader(entry)
        appendLine(" {")
        val hasInput = entry.inputType != Unit.serializer()
        append("        return this.fetcher<${entry.inputType.write()}, ${entry.outputType.write()}>(")
        append(
            listOf(
                "`${entry.path.path.escaped}`",
                "\"${entry.route.method}\"",
                if (hasInput) "input" else "undefined"
            ).joinToString(", ")
        )
        appendLine(")")
        appendLine("    }")
    }




    for (group in groups.sortedBy { it.groupToPartName() }) {
        val groupEndpoints = byGroup[group] ?: continue
        appendLine("    readonly ${group.groupToPartName()}: Api[\"${group.groupToPartName()}\"] = {")

        for (entry in groupEndpoints) {
            append("        ")
            append(entry.functionName)
            append(": ")
            this.functionHeader(entry, true)
            val hasInput = entry.inputType != Unit.serializer()
            append(" => this.fetcher(")
            append(
                listOf(
                    "`${entry.path.path.escaped}`",
                    "\"${entry.route.method}\"",
                    if (hasInput) "input" else "undefined"
                ).joinToString(", ")
            )
            appendLine("),")
        }
        appendLine("    }")
    }
    appendLine("}")
    appendLine()
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
    "DeepPartial",
    "Fetcher"
)
private val skipFromLsPackage = setOf("Partial") + fromLightningServerPackage

private fun String.groupToPartName(): String = replaceFirstChar { it.lowercase() }

@OptIn(ExperimentalSerializationApi::class)
private fun Appendable.functionHeader(documentable: Documentable, omitPartialType: Boolean = false) {
    if (documentable is ApiEndpoint<*, *, *, *>) {
        if (documentable.functionName == "queryPartial") {
            val args = arguments(documentable)
            val partialType = documentable.outputType.listElement()?.tryTypeParameterSerializers2()?.first()?.write() ?: return

            val otherArgs = args.joinToString(", ") {
                if ( it.name == "input") {
                    if (omitPartialType) {
                        "input: QueryPartial<${partialType}>"
                    } else {
                        "input: Q"
                    }
                } else {
                    "${it.name}${if (it.optional) "?" else ""}: ${it.type?.write() ?: it.stringType}"
                }
            }

                if (omitPartialType) {
                    append("(${otherArgs})")
                } else {
                    append("<const Q extends QueryPartial<${partialType}>>(${otherArgs}): Promise<Array<{[K in keyof ${partialType} as K extends Q[\"fields\"][number] ? K : never]: ${partialType}[K]}>>")
                }
            return@functionHeader
        }
    }
    append("(")
    var argComma = false
    arguments(documentable).forEach {
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

        is ApiWebsocket<*, *, *, *, *> -> {
            append("Observable<WebSocketIsh<")
            documentable.inputType.write().let { append(it) }
            append(", ")
            documentable.outputType.write().let { append(it) }
            append(">>")
        }

        else -> TODO()
    }
}

private data class TArg1(
    val name: String,
    val type: KSerializer<*>? = null,
    val stringType: String? = null,
    val default: String? = null,
    val optional: Boolean = false,
)

private fun arguments(
    documentable: Documentable,
): List<TArg1> = when (documentable) {
    is ApiEndpoint<*, *, *, *> -> listOfNotNull(
        documentable.path.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>()
            .mapIndexed { index, it ->
                TArg1(name = it.name, type = documentable.path.serializers[index], stringType = "String")
            },
        documentable.inputType.takeUnless { it == Unit.serializer() }?.let {
            TArg1(name = "input", type = it)
        }?.let(::listOf)
    ).flatten()

    is ApiWebsocket<*, *, *, *, *> -> listOfNotNull(
        documentable.path.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>()
            .map {
                TArg1(name = it.name, stringType = "string")
            }
    ).flatten()

    else -> TODO()
}


@OptIn(ExperimentalSerializationApi::class)
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
            PrimitiveKind.DOUBLE,
                -> out.append("number")

            PrimitiveKind.CHAR,
            PrimitiveKind.STRING,
                -> {
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
            StructureKind.CLASS,
                -> {
                if (descriptor.serialName == "com.lightningkite.serialization.Partial") {
                    out.append("DeepPartial")
                } else {
                    out.append(descriptor.simpleSerialName)
                }
                this.tryTypeParameterSerializers2()
                    ?.takeUnless { it.isEmpty() }
                    ?.joinToString(", ", "<", ">") { it.write() }
                    ?.let { out.append(it) }
            }
        }
    }.toString()
}

private val SerialDescriptor.simpleSerialName: String
    get() = serialName.substringBefore('<').substringBefore('/').substringAfterLast('.').removeSuffix("?")

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


@OptIn(ExperimentalSerializationApi::class)
private fun KSerializer<*>.getGenerics(): Array<KSerializer<*>>? {
    if (descriptor.kind is PolymorphicKind ||
        descriptor.kind is StructureKind.OBJECT ||
        descriptor.kind is SerialKind.ENUM ||
        descriptor.kind is StructureKind.CLASS
    ) {
        return this.tryTypeParameterSerializers2()
    }
    return null
}
