package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.auth.naturalLanguage
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.plus
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.LiveVersion
import com.lightningkite.lightningserver.typed.sdk.SDK.processToModules
import com.lightningkite.lightningserver.typed.sdk.SDK.sdk
import com.lightningkite.services.data.ExperimentalLightningServer

/**
 * Kotlin SDK generator using Fetcher-based HTTP clients.
 *
 * This [SDK.Format] implementation generates Kotlin/Multiplatform client code for a Lightning Server API,
 * creating type-safe interfaces and implementations that use the Lightning Server `Fetcher` for making
 * HTTP requests and WebSocket connections.
 *
 * ## Generated Code Structure
 *
 * The generator produces:
 * - **API Interface**: A Kotlin interface defining the client API contract with suspend functions
 * - **Live Implementation**: A concrete class implementing the API interface with Fetcher-based calls
 *
 * ## File Structure Options
 *
 * Choose between:
 * - **Single File**: All code in one `.kt` file (useful for small APIs)
 * - **Multiple Files**: Separated into interface and implementation files (recommended for larger APIs)
 *
 * ## Example Usage
 *
 * ```kotlin
 * // Basic usage with default settings
 * val format = FetcherSdk(
 *     packageName = "com.example.api",
 *     rootInfo = SdkModule.Info("MyApi")
 * )
 *
 * // Custom file structure
 * val format = FetcherSdk(
 *     packageName = "com.example.api.client",
 *     rootInfo = SdkModule.Info("MyApi"),
 *     fileStructure = FetcherSdk.Structure.SingleFile("ApiClient.kt"),
 *     includeDocComments = true
 * )
 *
 * // Generate to a directory
 * Archive.folder(outputDir).use { archive ->
 *     format.writeUsingDefaultSettings(serverBuilder, archive)
 * }
 * ```
 *
 * ## Features
 *
 * - **Type Safety**: Full Kotlin type safety with kotlinx.serialization
 * - **Suspend Functions**: All HTTP endpoints are suspend functions for coroutine support
 * - **WebSocket Support**: WebSocket handlers generate `ClientWebSocket` instances
 * - **Interface Extension**: Supports extending custom client interfaces with `@LiveVersion`
 * - **Header Injection**: Built-in support for dynamic header calculation (auth tokens, etc.)
 * - **Multiplatform**: Generated code works on all Kotlin/Multiplatform targets
 *
 * ## Requirements
 *
 * Generated code requires:
 * - `com.lightningkite.lightningserver:typed-shared` dependency for `Fetcher` and `ClientWebSocket`
 * - `kotlinx.serialization` for serialization support
 * - Shared models are defined in the same package as `packageName`
 * - Client interfaces must be annotated with `@LiveVersion` to reference their implementations
 *
 * @property packageName The package name for generated Kotlin files, must be the same package as model files
 * @property rootInfo The root module naming information for the generated API
 * @property fileStructure The file organization strategy (single file or multiple files)
 * @property includeDocComments Whether to include KDoc comments in the generated code.
 *                             Comments include endpoint summaries, descriptions, and auth requirements.
 *
 * @see SDK.Format
 * @see TypescriptFetcherSdk
 * @see Structure
 */
@OptIn(ExperimentalLightningServer::class)
public class FetcherSdk(
    public val packageName: String,
    public val rootInfo: SdkModule.Info = SdkModule.Info("Api"),
    public val fileStructure: Structure = Structure.MultipleFiles(
        interfaceFilename = "${rootInfo.interfaceName}.kt",
        liveFilename = "Live${rootInfo.interfaceName}.kt"
    ),
    public val includeDocComments: Boolean = true,
) : SDK.Format {
    /**
     * File organization strategies for generated Kotlin code.
     *
     * Determines how the generated SDK files are structured and named.
     *
     * @see SingleFile
     * @see MultipleFiles
     */
    public sealed interface Structure {
        public data class SingleFile(val filename: String) : Structure
        public data class MultipleFiles(
            val interfaceFilename: String,
            val liveFilename: String
        ) : Structure
    }

    /**
     * Generates Kotlin SDK files and writes them to the archive.
     *
     * This method:
     * 1. Extracts and processes the API structure from the server
     * 2. Generates Kotlin code (interface and implementation)
     * 3. Writes files according to the configured [fileStructure]
     *
     * @param archive The archive where generated files will be written
     */
    context(server: ServerRuntime)
    override fun write(archive: Archive) {
        val processed = server.server.sdk(rootInfo).processToModules().ensureUniqueNames()

        when (fileStructure) {
            is Structure.MultipleFiles -> {
                archive.appendableEntry(fileStructure.interfaceFilename) {
                    appendLine("package $packageName")
                    appendImports(processed)
                    writeInterface(processed)
                }
                archive.appendableEntry(fileStructure.liveFilename) {
                    appendLine("package $packageName")
                    appendImports(
                        processed,
                        "com.lightningkite.lightningserver.HttpMethod",
                        "com.lightningkite.lightningserver.typed.Fetcher",
                        "kotlinx.serialization.ContextualSerializer",
                        "kotlinx.serialization.builtins.serializer",
                        "kotlinx.serialization.builtins.MapSerializer",
                        "kotlinx.serialization.builtins.ListSerializer",
                        "kotlinx.serialization.builtins.nullable"
                    )
                    writeLive(processed)
                }
            }
            is Structure.SingleFile -> {
                archive.appendableEntry(fileStructure.filename) {
                    appendLine("package $packageName")
                    appendImports(
                        processed,
                        "com.lightningkite.lightningserver.HttpMethod",
                        "com.lightningkite.lightningserver.typed.Fetcher",
                        "kotlinx.serialization.ContextualSerializer",
                        "kotlinx.serialization.builtins.serializer",
                        "kotlinx.serialization.builtins.MapSerializer",
                        "kotlinx.serialization.builtins.ListSerializer",
                        "kotlinx.serialization.builtins.nullable"
                    )
                    writeInterface(processed)
                    writeLive(processed)
                }
            }
        }
    }

    private fun Appendable.appendImports(
        module: SDK.Module,
        vararg imports: String
    ) {
        fun SDK.Module.imports(): List<String> =
            extendsInterfaces.flatMap { it.item.imports } + children.flatMap { it.imports() }

        (imports.toList() + module.imports())
            .distinct()
            .joinTo(this, "\n", prefix = "\n", postfix = "\n") { "import $it" }
    }

    context(_: ServerRuntime)
    private fun Appendable.writeInterface(module: SDK.Module) {
        fun SDK.Module.writeInterface(depth: Int) {

            val singleInterface = extendsInterfaces.singleOrNull()?.item?.takeIf { declaredFunctions.isEmpty() && children.isEmpty() && depth > 0 }

            appendLine()

            if (singleInterface == null) {
                appendIdtLine(depth, "interface ${info.interfaceName}" + (if (extendsInterfaces.isEmpty()) "" else " : ${extendsInterfaces.joinToString { it.item.kotlinString() }}") + " {")

                if (depth == 0) appendIdtLine(1, "fun withHeaderCalculator(calculator: suspend () -> List<Pair<String, String>>): ${info.interfaceName}")

                for (function in declaredFunctions) {
                    if (includeDocComments) {
                        val docs = buildList {
                            fun line(string: String) {
                                add(string); add("")
                            }
                            if (function.summary.isNotBlank()) line(function.summary)
                            if (function.description.isNotBlank()) line(function.description)

                            add("**Auth Requirements:** ${function.auth.naturalLanguage(true).replace("[", "[[").replace("]", "]]")}")
                        }

                        appendIdtLine(depth + 1, "/**")
                        for (line in docs) appendIdtLine(depth + 1, " * $line")
                        appendIdtLine(depth + 1, " * */")
                    }

                    appendIdtLine(depth + 1, function.kotlinString())
                }

                for (module in children) module.writeInterface(depth + 1)

                appendIdtLine(depth, "}")
            }

            if (depth > 0) appendIdtLine(depth, "val ${info.valueName}: ${singleInterface?.kotlinString() ?: (info.interfaceName)}")
        }

        module.writeInterface(0)
    }

    context(server: ServerRuntime)
    private fun Appendable.writeLive(module: SDK.Module) {
        fun PathSpec.toCodeString() = segments.joinToString("/", prefix = "\"", postfix = "\"") {
            when (it) {
                is PathSpec.Segment.Constant -> it.value
                is PathSpec.Segment.Wildcard<*> -> $$"${fetcher.url($${it.name.functionCase()}, $${it.serializer.kotlinSerializer()})}"
            }
        }

        fun InterfaceInfo.liveString(path: PathSpec): String {
            val live = type.annotations
                .filterIsInstance<LiveVersion>()
                .firstOrNull()
                ?.live
                ?.let { it.qualifiedName ?: throw SDK.GenerationException("No qualified name found for live version of $this: $it") }
                ?: throw SDK.GenerationException("LiveVersion annotation required on client interfaces, please provide a live version of ${type.qualifiedName}.")

            return "$live(fetcher, ${path.toCodeString()}, ${typeParameters.joinToString { it.kotlinSerializer() }})"
        }

        fun SDK.Module.writeLive(ancestors: List<SDK.Module>) {
            val depth = ancestors.size

            val pathPrefix = (ancestors + this).fold(PathSpec.root) { acc, mod -> acc + mod.path }
            fun PathSpec.absolute(): PathSpec = pathPrefix + this

            val singleInterface = extendsInterfaces.singleOrNull()?.takeIf { declaredFunctions.isEmpty() && depth > 0 }

            appendLine()

            if (singleInterface == null) {
                val extendsInterfaces = listOf((ancestors + this).joinToString(".") { it.info.interfaceName }) + extendsInterfaces.map { inter ->
                    "${inter.item.kotlinString()} by ${inter.item.liveString(pathPrefix + inter.location)}"
                }

                if (depth == 0) {
                    appendLine("class Live${info.interfaceName}(val fetcher: Fetcher) : ${extendsInterfaces.joinToString()} {")

                    appendIdtLine(1, "override fun withHeaderCalculator(calculator: suspend () -> List<Pair<String, String>>): Live${info.interfaceName} = ")
                    appendIdtLine(2, "Live${info.interfaceName}(fetcher.withHeaderCalculator(calculator))")
                }
                else appendIdtLine(depth, "inner class Live${info.interfaceName} : ${extendsInterfaces.joinToString()} {")

                for (function in declaredFunctions) {
                    appendIdtLine(depth + 1, "override ${function.kotlinString()} =")
                    appendIdtLine(
                        depth + 2,
                        when (function) {
                            is SDK.Function.Endpoint -> "fetcher(" + listOf(
                                function.path.absolute().toCodeString(),
                                "HttpMethod.${function.endpoint.method}",
                                function.inputType.kotlinSerializer(),
                                if (function.inputType.isUnit()) "kotlin.Unit" else "input",
                                function.outputType.kotlinSerializer()
                            ).joinToString() + ')'

                            is SDK.Function.Websocket -> "fetcher.websocket(" + listOf(
                                function.path.absolute().toCodeString(),
                                function.inputType.kotlinSerializer(),
                                function.outputType.kotlinSerializer()
                            ).joinToString() + ')'
                        }
                    )
                }

                for (module in children) module.writeLive(ancestors + this)

                appendIdtLine(depth, "}")
            }

            if (depth > 0) appendIdtLine(depth, "override val ${info.valueName} = ${singleInterface?.item?.liveString(pathPrefix + singleInterface.location) ?: "Live${info.interfaceName}()"}")
        }

        module.writeLive(emptyList())
    }

    private fun SDK.Function.kotlinString(): String {
        val argString = arguments.joinToString { "${it.name.functionCase()}: ${it.type.kotlinTypeString()}" }
        return when (this) {
            is SDK.Function.Endpoint ->
                "suspend fun $functionName($argString)" + if (outputType.isUnit()) "" else ": ${outputType.kotlinTypeString()}"

            is SDK.Function.Websocket ->
                "fun $functionName($argString): ClientWebSocket<${inputType.kotlinTypeString()}, ${outputType.kotlinTypeString()}>"
        }
    }
}