package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.auth.naturalLanguage
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.plus
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.sdk.SDK.processToModules
import com.lightningkite.lightningserver.typed.sdk.SDK.sdk
import com.lightningkite.services.data.ExperimentalLightningServer
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


/**
 * TypeScript SDK generator using Fetcher-based HTTP clients.
 *
 * This [SDK.Format] implementation generates TypeScript client code for a Lightning Server API,
 * creating type-safe interfaces and implementations that use the `@lightningkite/lightning-server-simplified`
 * Fetcher for making HTTP requests.
 *
 * ## Generated Code Structure
 *
 * The generator produces:
 * - **Type Definitions**: TypeScript interfaces and enums for all data models
 * - **API Interface**: A TypeScript interface defining the client API contract
 * - **Live Implementation**: A concrete class implementing the API interface with Fetcher-based HTTP calls
 *
 * ## File Structure Options
 *
 * Choose between:
 * - **Single File**: All code in one `.ts` file (useful for small APIs)
 * - **Multiple Files**: Separated into models, interface, and implementation files (recommended for larger APIs)
 *
 * ## Example Usage
 *
 * ```kotlin
 * // Basic usage with default settings
 * val format = TypescriptFetcherSdk(
 *     rootInfo = SdkModule.Info("MyApi")
 * )
 *
 * // Custom file structure
 * val format = TypescriptFetcherSdk(
 *     rootInfo = SdkModule.Info("MyApi"),
 *     fileStructure = TypescriptFetcherSdk.Files.SingleFile("api-client.ts"),
 *     includeDocComments = true
 * )
 *
 * // Generate to a ZIP file
 * Archive.zip(ZipOutputStream(outputStream)).use { archive ->
 *     format.writeUsingDefaultSettings(serverBuilder, archive)
 * }
 * ```
 *
 * ## Generated TypeScript Example
 *
 * For a server with user endpoints, this generates:
 *
 * ```typescript
 * // Interface
 * export interface MyApi {
 *     users: {
 *         list(): Promise<User[]>
 *         create(input: CreateUserRequest): Promise<User>
 *     }
 * }
 *
 * // Implementation
 * export class LiveMyApi implements MyApi {
 *     constructor(public fetcher: Fetcher) {}
 *
 *     users = {
 *         list: () => this.fetcher("/api/users/list", "GET", undefined),
 *         create: (input) => this.fetcher("/api/users/create", "POST", input)
 *     }
 * }
 * ```
 *
 * ## Type Mapping
 *
 * Kotlin types are mapped to TypeScript as follows:
 * - `String`, `Char` → `string`
 * - `Int`, `Long`, `Float`, `Double` → `number`
 * - `Boolean` → `boolean`
 * - `List<T>` → `Array<T>`
 * - `Map<String, V>` → `Record<string, V>`
 * - `T?` → `T | null | undefined`
 * - Data classes → TypeScript interfaces
 * - Enums → TypeScript enums
 *
 * ## Requirements
 *
 * Generated code requires the `@lightningkite/lightning-server-simplified` npm package
 * which provides the `Fetcher` type and utility types like `Query`, `Modification`, etc.
 *
 * @property rootInfo The root module naming information for the generated API
 * @property fileStructure The file organization strategy (single file or multiple files)
 * @property includeDocComments Whether to include JSDoc comments in the generated code.
 *                             Comments include endpoint summaries, descriptions, and auth requirements.
 *
 * @see SDK.Format
 * @see FetcherSdk
 * @see Structure
 */
@OptIn(ExperimentalLightningServer::class)
public class TypescriptFetcherSdk(
    public val rootInfo: SdkModule.Info = SdkModule.Info("Api"),
    public val fileStructure: Structure = Structure.MultipleFiles(
        modelsFilename = "models.ts",
        interfaceFilename = "${rootInfo.interfaceName}.ts",
        liveFilename = "Live${rootInfo.interfaceName}.ts"
    ),
    public val includeDocComments: Boolean = true
) : SDK.Format {
    /**
     * File organization strategies for generated TypeScript code.
     *
     * Determines how the generated SDK files are structured and named.
     *
     * @see SingleFile
     * @see MultipleFiles
     */
    public sealed interface Structure {
        public data class SingleFile(val filename: String) : Structure
        public data class MultipleFiles(
            val modelsFilename: String,
            val interfaceFilename: String,
            val liveFilename: String
        ) : Structure
    }

    /**
     * Generates TypeScript SDK files and writes them to the archive.
     *
     * This method:
     * 1. Extracts and processes the API structure from the server
     * 2. Generates TypeScript code (types, interface, implementation)
     * 3. Writes files according to the configured [fileStructure]
     *
     * @param archive The archive where generated files will be written
     */
    context(server: ServerRuntime)
    override fun write(archive: Archive): Unit = when (fileStructure) {
        is Structure.SingleFile -> {
            val processed = server.server.sdk(rootInfo).processToModules().ensureUniqueNames()

            fun Appendable.bigGap() = append("\n\n\n")

            archive.appendableEntry(fileStructure.filename) {
                appendLsImports()
                appendLine()
                writeTypeDefinitions()
                bigGap()
                writeInterface(processed)
                bigGap()
                writeLive(processed)
            }
        }
        is Structure.MultipleFiles -> {
            val processed = server.server.sdk(rootInfo).processToModules().ensureUniqueNames()

            val models = server.models()

            fun Appendable.appendModelImports() =
                appendLine("import type { ${models.joinToString { it.tsType().substringBefore('<') }} } from './${fileStructure.modelsFilename}'")

            archive.appendableEntry(fileStructure.modelsFilename) {
                appendLsImports()
                appendLine()
                writeTypeDefinitions(models)
            }

            archive.appendableEntry(fileStructure.interfaceFilename) {
                appendLsImports()
                appendModelImports()
                appendLine()
                writeInterface(processed)
            }

            archive.appendableEntry(fileStructure.liveFilename) {
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

    /**
     * Generates the TypeScript interface definition for the API.
     *
     * Creates a hierarchical interface structure with:
     * - Methods for each endpoint (returning Promises)
     * - Nested properties for sub-modules
     * - JSDoc comments (if enabled) with summaries, descriptions, and auth requirements
     *
     * @param root The root module to generate an interface for
     */
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