package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.auth.naturalLanguage
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.plus
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.sdk.SDK.processToModules
import com.lightningkite.lightningserver.typed.sdk.SDK.sdk
import com.lightningkite.services.data.KFile
import com.lightningkite.services.database.MySealedClassSerializer
import com.lightningkite.services.database.childSerializersOrNull
import com.lightningkite.services.database.listElement
import com.lightningkite.services.database.mapValueElement
import com.lightningkite.services.database.nullElement
import com.lightningkite.services.database.serializableProperties
import com.lightningkite.services.database.typeParametersSerializersOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlin.collections.fold
import kotlin.collections.plus

public class TypescriptFetcherSDK(
    public val rootInfo: SdkModule.Info = SdkModule.Info("Api"),
    public val fileStructure: Files = Files.MultipleFiles(
        modelsFilename = "models.ts",
        interfaceFilename = "${rootInfo.interfaceName}.ts",
        liveFilename = "Live${rootInfo.interfaceName}.ts"
    ),
    public val includeDocComments: Boolean = true
) : SDK.Format {
    public sealed interface Files {
        public data class SingleFile(val filename: String) : Files

        public data class MultipleFiles(
            val modelsFilename: String,
            val interfaceFilename: String,
            val liveFilename: String
        ) : Files
    }

    context(server: ServerRuntime)
    override fun write(folder: KFile): Unit = when (fileStructure) {
        is Files.SingleFile -> {
            val processed = server.server.sdk(rootInfo).processToModules().ensureUniqueNames()

            fun Appendable.bigGap() = append("\n\n\n")

            folder.then(fileStructure.filename).overwrite {
                appendLsImports()
                appendLine()
                writeTypeDefinitions()
                bigGap()
                writeInterface(processed)
                bigGap()
                writeLive(processed)
            }
        }
        is Files.MultipleFiles -> {
            val processed = server.server.sdk(rootInfo).processToModules().ensureUniqueNames()

            val models = server.models()

            fun Appendable.appendModelImports() =
                appendLine("import type { ${models.joinToString { it.tsType().substringBefore('<') }} } from './${fileStructure.modelsFilename}'")

            folder.then(fileStructure.modelsFilename).overwrite {
                appendLsImports()
                appendLine()
                writeTypeDefinitions(models)
            }

            folder.then(fileStructure.interfaceFilename).overwrite {
                appendLsImports()
                appendModelImports()
                appendLine()
                writeInterface(processed)
            }

            folder.then(fileStructure.liveFilename).overwrite {
                appendLsImports()
                appendModelImports()
                appendLine("import type { ${rootInfo.interfaceName} } from './${fileStructure.interfaceFilename}'")
                appendLine()
                writeLive(processed)
            }
        }
    }

    private fun Appendable.appendLsImports() =
        appendLine("import type { ${fromLightningServerPackage.joinToString()} } from '@lightningkite/lightning-server-simplified'")

    context(server: ServerRuntime)
    private fun Appendable.writeTypeDefinitions(types: List<KSerializer<*>> = server.models()) {
        val stringSerialNames = HashSet<String>()

        for (type in types) {
            when (type.descriptor.kind) {
                StructureKind.CLASS -> {
                    val genericMap: Map<String, String> = type
                        .getGenerics()
                        ?.withIndex()
                        ?.associate { (index, value) ->
                            value.tsType() to "T${if (index > 0) index else ""}"
                        }
                        ?: emptyMap()

                    fun String.replaceGenerics(): String =
                        genericMap.entries.fold(this) { acc, (old, new) -> acc.replace(old, new) }

                    appendLine("export interface ${type.tsType().replaceGenerics()} {")

                    val properties = type
                        .serializableProperties?.map { it.serializer }
                        ?: type.childSerializersOrNull()?.toList()
                        ?: emptyList()

                    for ((idx, prop) in properties.withIndex()) {
                        appendLine("\t${type.descriptor.getElementName(idx)}: ${prop.tsType().replaceGenerics()}")
                    }

                    appendLine('}')
                }

                SerialKind.ENUM -> {
                    appendLine("export enum ${type.tsType()} {")
                    for (index in 0 until type.descriptor.elementsCount) {
                        append('\t')
                        val name = type.descriptor.getElementName(index)
                        name.forEachIndexed { idx, it ->
                            if ((idx == 0 && it.isJavaIdentifierStart()) || (idx != 0 && it.isJavaIdentifierPart()))
                                append(it)
                            else
                                append('_')
                        }
                        append(" = \"$name\",")
                        appendLine()
                    }
                    appendLine('}')
                }

                PrimitiveKind.STRING -> {
                    val name = type.descriptor.simpleSerialName
                    if (name != "String" && stringSerialNames.add(name)) {
                        appendLine(
                            "export type $name = string  // ${type.descriptor.serialName}"
                                .replace("/loose", "")
                        )
                    }
                }

                else -> continue
            }
            appendLine()
        }
    }

    context(server: ServerRuntime)
    private fun Appendable.writeInterface(root: SDK.Module) {
        fun Appendable.appendInterface(module: SDK.Module, depth: Int) {
            if (depth == 0) appendLine("export interface ${module.info.interfaceName} {")
            else appendIdtLine(depth, "readonly ${module.info.valueName}: {")

            for (func in module.functions.filter { it is SDK.Function.Endpoint }) {
                if (includeDocComments) {
                    val docs = buildList {
                        fun line(string: String) {
                            add(string); add("")
                        }
                        if (func.summary.isNotBlank()) line(func.summary)
                        if (func.description.isNotBlank()) line(func.description)

                        add("**Auth Requirements:** ${func.auth.naturalLanguage(true).replace("[", "[[").replace("]", "]]")}")
                    }

                    appendIdtLine(depth + 1, "/**")
                    for (line in docs) appendIdtLine(depth + 1, " * $line")
                    appendIdtLine(depth + 1, " * */")
                }

                appendIdt(depth + 1)
                append(func.functionName)
                func.arguments.joinTo(this, ", ", "(", "): ") {
                    "${it.name}: ${it.type.tsType()}"
                }
                when (func) {
                    is SDK.Function.Endpoint -> append("Promise<${func.outputType.tsType()}>")
                    is SDK.Function.Websocket -> {
                        // Websockets not supported yet
                    }
                }
                appendLine()
            }

            if (module.children.isNotEmpty()) appendLine()

            for (child in module.children) appendInterface(child, depth + 1)

            appendIdtLine(depth, '}')
        }

        appendInterface(root, 0)
    }

    context(server: ServerRuntime)
    private fun Appendable.writeLive(root: SDK.Module) {
        fun PathSpec.ts() = segments.joinToString("/", prefix = "`/", postfix = "`") {
            when (it) {
                is PathSpec.Segment.Constant -> it.value
                is PathSpec.Segment.Wildcard<*> -> $$"${$${it.name}}"
            }
        }

        fun Appendable.appendLive(module: SDK.Module, ancestors: List<SDK.Module>) {
            val depth = ancestors.size

            val pathPrefix = (ancestors + module).fold(PathSpec.root) { acc, mod -> acc + mod.path }
            fun PathSpec.absolute(): PathSpec = pathPrefix + this

            if (depth == 0) {
                appendLine("export class Live${module.info.interfaceName} implements ${module.info.interfaceName} {")
                appendIdt(1).appendLine("public constructor(public fetcher: Fetcher) {}")
                appendLine()
            }
            else {
                appendIdt(depth)
                if (depth == 1) append("readonly ")
                append(module.info.valueName)
                if (depth == 1) {
                    append(": Api")
                    for (mod in ancestors.drop(1) + module) append("[\"${mod.info.valueName}\"]")
                    append(" = {")
                }
                else append(": {")
                appendLine()
            }

            for (func in module.functions.filter { it is SDK.Function.Endpoint }) {
                appendIdt(depth + 1)
                append(func.functionName)

                if (depth == 0) append(": ${module.info.interfaceName}[\"${func.functionName}\"] = ")
                else append(": ")

                func.arguments.joinTo(this, ", ", "(", ")") { it.name }
                append(" => ")

                when (func) {
                    is SDK.Function.Endpoint -> {
                        append("this.fetcher(")
                        listOf(
                            func.path.absolute().ts(),
                            "\"${func.endpoint.method}\"",
                            if (func.inputType.isUnit()) "undefined" else "input"
                        ).joinTo(this)
                        append(')')
                        if (depth > 0) append(',')
                        appendLine()
                    }
                    is SDK.Function.Websocket -> {
                        // websockets not supported yet
                    }
                }
            }

            if (module.children.isNotEmpty()) appendLine()

            for (child in module.children) appendLive(child, ancestors + module)

            appendIdt(depth)
            append('}')
            if (depth > 1) append(',')
            appendLine()
        }

        appendLive(root, emptyList())
    }


    private fun ServerRuntime.models() = usedTypes()
        .filter { it.descriptor.simpleSerialName !in skipFromLsPackage }
        .sortedBy { it.descriptor.simpleSerialName }
        .distinctBy { it.tsType() }
        .filter {
            when (it.descriptor.kind) {
                SerialKind.ENUM -> true
                StructureKind.CLASS if (it !is MySealedClassSerializer) -> true
                PrimitiveKind.STRING if (it.descriptor.simpleSerialName != "String") -> true

                else -> false
            }
        }


    @OptIn(ExperimentalSerializationApi::class)
    context(runtime: ServerRuntime)
    private fun KSerializer<*>.tsType(): String = nullElement()?.let { it.tsType() + " | null | undefined" } ?: when {
        this.isUnit() -> "void"
        else -> buildString {
            when (descriptor.kind) {
                PrimitiveKind.BOOLEAN -> append("boolean")

                PrimitiveKind.BYTE,
                PrimitiveKind.SHORT,
                PrimitiveKind.INT,
                PrimitiveKind.LONG,
                PrimitiveKind.FLOAT,
                PrimitiveKind.DOUBLE -> append("number")

                PrimitiveKind.CHAR,
                PrimitiveKind.STRING -> {
                    val cleanName = descriptor.simpleSerialName
                    if (cleanName != "String") {
                        append(cleanName)
                        subSerializers().takeUnless { it.isEmpty() }?.joinTo(this, ", ", "<", ">") { it.tsType() }
                    } else {
                        append("string")
                    }
                }

                StructureKind.LIST -> {
                    append("Array<${listElement()!!.tsType()}>")
                }

                StructureKind.MAP -> {
                    append("Record<string, ${mapValueElement()!!.tsType()}>")
                }

                SerialKind.CONTEXTUAL -> {
                    append(decontextualize().tsType())
                }

                is PolymorphicKind,
                StructureKind.OBJECT,
                SerialKind.ENUM,
                StructureKind.CLASS -> {
                    if (descriptor.serialName == "com.lightningkite.serialization.Partial") {
                        append("DeepPartial")
                    } else {
                        append(descriptor.simpleSerialName)
                    }
                    typeParametersSerializersOrNull()
                        ?.takeUnless { it.isEmpty() }
                        ?.joinTo(this, ", ", "<", ">") { it.tsType() }
                }
            }
        }
    }

    private val SerialDescriptor.simpleSerialName: String
        get() = serialName.substringBefore('<').substringBefore('/').substringAfterLast('.').removeSuffix("?")

    @OptIn(ExperimentalSerializationApi::class)
    private fun KSerializer<*>.getGenerics(): Array<KSerializer<*>>? = when (descriptor.kind) {
        is PolymorphicKind,
        StructureKind.OBJECT,
        SerialKind.ENUM,
        StructureKind.CLASS -> typeParametersSerializersOrNull()

        else -> null
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
}