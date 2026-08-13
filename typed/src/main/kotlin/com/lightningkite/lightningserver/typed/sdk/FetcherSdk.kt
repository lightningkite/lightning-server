package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.auth.naturalLanguage
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpecMany
import com.lightningkite.lightningserver.pathing.plus
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.LiveVersion
import com.lightningkite.lightningserver.typed.sdk.SDK.processToModules
import com.lightningkite.lightningserver.typed.sdk.SDK.sdk
import com.lightningkite.services.data.ExperimentalLightningServer
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.full.functions

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
            val liveFilename: String,
            val generatedLiveVersionsFilename: String = "GeneratedLiveVersions.kt"
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
        val data = server.server.sdk(rootInfo)
        val processed = data.processToModules().ensureUniqueNames()

        val needToGenerateLiveVersions = data.asSequence().any { node ->
            node.layer.endpoints.keys.any { it?.item?.needsGeneration() == true }
        }

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
                        "kotlinx.serialization.builtins.*",
                        "kotlinx.serialization.ExperimentalSerializationApi"
                    )
                    writeLive(processed)
                }
                if (needToGenerateLiveVersions) archive.appendableEntry(fileStructure.generatedLiveVersionsFilename) {
                    appendLine("package $packageName")
                    appendImports(
                        processed,
                        "com.lightningkite.lightningserver.HttpMethod",
                        "com.lightningkite.lightningserver.typed.Fetcher",
                        "com.lightningkite.lightningserver.typed.ClientWebSocket",
                        "kotlinx.serialization.ContextualSerializer",
                        "kotlinx.serialization.KSerializer",
                        "kotlinx.serialization.builtins.*",
                    )
                    appendLine()
                    writeGeneratedLiveVersions(data)
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
                        "kotlinx.serialization.builtins.*",
                        "kotlinx.serialization.ExperimentalSerializationApi"
                    )
                    writeInterface(processed)
                    writeLive(processed)
                    if (needToGenerateLiveVersions) writeGeneratedLiveVersions(data)
                }
            }
        }
    }

    private fun Appendable.appendImports(
        module: SDK.Module,
        vararg imports: String,
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

            val singleInterface =
                extendsInterfaces.singleOrNull()?.item?.takeIf { declaredFunctions.isEmpty() && children.isEmpty() && depth > 0 }

            appendLine()

            if (singleInterface == null) {
                appendIdtLine(
                    depth,
                    "interface ${info.interfaceName}" + (if (extendsInterfaces.isEmpty()) "" else " : ${extendsInterfaces.joinToString { it.item.kotlinString() }}") + " {"
                )

                if (depth == 0) appendIdtLine(
                    1,
                    "fun withHeaderCalculator(calculator: suspend () -> List<Pair<String, String>>): ${info.interfaceName}"
                )

                for (function in declaredFunctions) {
                    if (includeDocComments) append(function.kdoc(depth, includeAuth = true))

                    appendIdtLine(depth + 1, function.kotlinString())
                }

                for (module in children) module.writeInterface(depth + 1)

                appendIdtLine(depth, "}")
            }

            if (depth > 0) appendIdtLine(
                depth,
                "val ${info.valueName}: ${singleInterface?.kotlinString() ?: (info.interfaceName)}"
            )
        }

        module.writeInterface(0)
    }

    context(server: ServerRuntime)
    private fun Appendable.writeLive(module: SDK.Module) {
        fun InterfaceInfo.liveString(path: PathSpec): String {
            val live = type.annotations
                .filterIsInstance<LiveVersion>()
                .firstOrNull()

            val name = live?.live
                ?.let { it.qualifiedName ?: throw IllegalStateException("LiveVersion for $type has no qualified name") }
                ?: type.simpleName?.let { "Live$it" } // generated name, will be in same package.
                ?: throw IllegalStateException("$type has no simple name")

            return "$name(fetcher, ${path.toCodeString()}, ${typeParameters.joinToString { it.kotlinSerializer() }})"
        }

        fun SDK.Module.writeLive(ancestors: List<SDK.Module>) {
            val depth = ancestors.size

            val pathPrefix = (ancestors + this).fold(PathSpec.root) { acc, mod -> acc + mod.path }
            fun PathSpec.absolute(): PathSpec = pathPrefix + this

            val singleInterface = extendsInterfaces.singleOrNull()?.takeIf {
                depth > 0 && declaredFunctions.isEmpty() && children.isEmpty()
            }

            appendLine()

            if (singleInterface == null) {
                val extendsInterfaces =
                    listOf((ancestors + this).joinToString(".") { it.info.interfaceName }) + extendsInterfaces.map { inter ->
                        "${inter.item.kotlinString()} by ${inter.item.liveString(pathPrefix + inter.location)}"
                    }

                if (depth == 0) {
                    appendLine("@OptIn(ExperimentalSerializationApi::class)")
                    appendLine("class Live${info.interfaceName}(val fetcher: Fetcher) : ${extendsInterfaces.joinToString()} {")

                    appendIdtLine(
                        1,
                        "override fun withHeaderCalculator(calculator: suspend () -> List<Pair<String, String>>): Live${info.interfaceName} = "
                    )
                    appendIdtLine(2, "Live${info.interfaceName}(fetcher.withHeaderCalculator(calculator))")
                } else appendIdtLine(
                    depth,
                    "inner class Live${info.interfaceName} : ${extendsInterfaces.joinToString()} {"
                )

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

            if (depth > 0) appendIdtLine(
                depth,
                "override val ${info.valueName} = ${singleInterface?.item?.liveString(pathPrefix + singleInterface.location) ?: "Live${info.interfaceName}()"}"
            )
        }

        module.writeLive(emptyList())
    }

    private fun InterfaceInfo.needsGeneration() =
        type.annotations.none { it is LiveVersion }

    context(server: ServerRuntime)
    private fun Appendable.writeGeneratedLiveVersions(data: SDK.Data) {
        data.asSequence()
            .flatMap { it.layer.endpoints.entries }
            .filter {
                it.key?.item?.needsGeneration() == true
            }
            .distinctBy { it.key?.item?.type }
            .sortedBy { it.key?.item?.type?.simpleName }
            .forEach { (locationed, endpoints) ->
                val inter = locationed?.item ?: return@forEach

                if (inter.typeParameters.size != inter.type.typeParameters.size)
                    throw SDK.GenerationException("Cannot generate live version for $inter: number of provided type parameter serializers does not match number of type parameters (${inter.typeParameters.size} vs ${inter.type.typeParameters.size})")

                val name = inter.type.simpleName ?: throw SDK.GenerationException("Cannot generate live version for $inter: no simple name")

                // for now it's assumed that any ServerBuilder that sets interfaceInfo the defined endpoints are a 1-to-1 match

                val endpointEntries = endpoints.entries

                val matched = inter.type.functions.filterNot { it.name == "equals" || it.name == "toString" || it.name == "hashCode" }.map { func ->
                    endpointEntries.firstNotNullOfOrNull { (path, endpoints) ->
                        val path = PathSpecMany(path.segments.drop(locationed.location.segments.size), path.after, path.wildcards)
                        endpoints.http.entries
                            .find { it.value.functionName == func.name }
                            ?.let {
                                SDK.Function.Endpoint(
                                    handler = it.value,
                                    endpoint = HttpEndpoint(path, it.key),
                                    fromInterface = inter,
                                    functionName = it.value.functionName
                                )
                            }
                            ?: endpoints.websocket?.takeIf { it.functionName == func.name }?.let { websocket ->
                                SDK.Function.Websocket(
                                    handler = websocket,
                                    path = path,
                                    fromInterface = inter,
                                    functionName = websocket.functionName
                                )
                            }
                    }
                    ?: throw SDK.GenerationException("Cannot generate live version for $inter: No endpoint with required method functionName \"${func.name}\" found.")
                }

                val paramsSerializersToValues = inter.typeParameters.zip(inter.type.typeParameters).map { (serializer, param) ->
                    Triple(param, serializer, "${param.name.lowercase()}Serializer")
                }

                //  Generation Format:
                //  open class Live{interfaceName}[<{args [: upperBound]},...>](
                //      val fetcher: Fetcher,
                //      val basePath: String,
                //      [val {arg1}Serializer: KSerializer<{arg1}>,]
                //      [val {arg2}Serializer: KSerializer<{arg2}>,]
                //      ...
                //  ): {interfaceName}[<{args},*>] [where {arg1}: {upperBound1}, {arg1}: {upperBound2}, ...]{
                //

                append("open class Live$name")
                inter.type.typeParameters.joinToIfNotEmpty(this, prefix = "<", postfix = ">") { param ->
                    val singleUpperBound = param.upperBounds.singleOrNull()
                    if (singleUpperBound == null) param.name
                    else "${param.name}: ${singleUpperBound.kotlinString()}"
                }
                appendLine("(")

                appendIdtLine(1, "val fetcher: Fetcher,")
                appendIdtLine(1, "val basePath: String,")
                paramsSerializersToValues.joinToIfNotEmpty(this, ",\n", postfix = "\n") { (param, _, valueName) ->
                    "\tval $valueName: KSerializer<${param.name}>"
                }

                append("): ${inter.type.qualifiedName}")    // ): ExtendsInterface
                inter.type.typeParameters.joinToIfNotEmpty(this, prefix = "<", postfix = ">") { it.name } // ExtendsInterface<A, B, ...>

                inter.type.typeParameters   // optional where clauses
                    .filter { it.upperBounds.size > 1 }
                    .flatMap { param -> param.upperBounds.map { param to it } }
                    .joinToIfNotEmpty(this, prefix = "where ") { (param, upperBound) ->
                        "${param.name}: ${upperBound.kotlinString()}"
                    }

                appendLine(" {")

                val typeReplacements = paramsSerializersToValues.associate { it.second to it.first.name }
                val serializerReplacements = paramsSerializersToValues.associate { it.second to it.third }

                for ((idx, function) in matched.withIndex()) {
                    if (idx > 0) appendLine()
                    if (includeDocComments) append(function.kdoc(0, includeAuth = false))
                    append("\toverride ${function.kotlinString(typeReplacements)} = ")
                    when (function) {
                        is SDK.Function.Endpoint -> {
                            appendLine("fetcher(")
                            appendIdtLine(
                                2,
                                if (function.path.let { it.segments.isEmpty() && it.after == PathSpec.Afterwards.None }) "basePath,"
                                else $$"\"$basePath/$${function.path.toCodeString(false, serializerReplacements)}\","
                            )
                            appendIdtLine(2, "HttpMethod.${function.endpoint.method},")
                            appendIdtLine(2, function.inputType.kotlinSerializerWithReplacements(serializerReplacements) + ",")
                            appendIdtLine(2, if (function.inputType.isUnit()) "kotlin.Unit," else "input,")
                            appendIdtLine(2, function.outputType.kotlinSerializerWithReplacements(serializerReplacements))
                        }
                        is SDK.Function.Websocket -> {
                            appendLine("fetcher.websocket(")
                            appendIdtLine(
                                2,
                                if (function.path.let { it.segments.isEmpty() && it.after == PathSpec.Afterwards.None }) "basePath,"
                                else $$"\"$basePath/$${function.path.toCodeString(false, serializerReplacements)}\","
                            )
                            appendIdtLine(2, function.inputType.kotlinSerializerWithReplacements(serializerReplacements) + ",")
                            appendIdtLine(2, function.outputType.kotlinSerializerWithReplacements(serializerReplacements))
                        }
                    }
                    appendIdtLine(1, ")")
                }

                appendLine("}")
                appendLine()
            }
    }

    private fun KType.kotlinString(): String = buildString {
        val classifier = classifier ?: throw SDK.GenerationException("No classifier provided for ${this@kotlinString}")
        val name = (classifier as? KClass<*>)
            ?.let { it.qualifiedName ?: throw SDK.GenerationException("No qualified name for KClass $it") }
            ?: (classifier as? KTypeParameter)?.name
            ?: throw SDK.GenerationException("Classifier was not a KClass or KTypeParameter (This shouldn't happen)")

        append(name)

        arguments.joinToIfNotEmpty(this, prefix = "<", postfix = ">") { arg ->
            arg.type?.kotlinString() ?: "*"
        }
    }

    private fun <T, A : Appendable> Collection<T>.joinToIfNotEmpty(
        buffer: A,
        separator: CharSequence = ", ",
        prefix: CharSequence = "",
        postfix: CharSequence = "",
        transform: ((T) -> CharSequence)? = null
    ) {
        if (this.isNotEmpty()) joinTo(buffer, separator, prefix, postfix, transform = transform)
    }

    context(_: ServerRuntime)
    private fun PathSpec.toCodeString(
        quoted: Boolean = true,
        replacements: Map<KSerializer<*>, String> = emptyMap()
    ) = segments.joinToString(
        separator = "/",
        prefix = if (quoted) "\"" else "", postfix = if (quoted) "\"" else ""
    ) {
        when (it) {
            is PathSpec.Segment.Constant -> it.value
            is PathSpec.Segment.Wildcard<*> -> $$"${fetcher.url($${it.name.functionCase()}, $${it.serializer.kotlinSerializerWithReplacements(replacements)})}"
        }
    }

    private fun SDK.Function.kotlinString(replacements: Map<KSerializer<*>, String> = emptyMap()): String {
        val argString = arguments.joinToString { "${it.name.functionCase()}: ${it.type.kotlinTypeStringWithReplacements(replacements)}" }
        return when (this) {
            is SDK.Function.Endpoint ->
                "suspend fun $functionName($argString)" + if (outputType.isUnit()) "" else ": ${outputType.kotlinTypeStringWithReplacements(replacements)}"

            is SDK.Function.Websocket ->
                "fun $functionName($argString): ClientWebSocket<${inputType.kotlinTypeStringWithReplacements(replacements)}, ${outputType.kotlinTypeStringWithReplacements(replacements)}>"
        }
    }

    context(_: ServerRuntime)
    private fun SDK.Function.kdoc(depth: Int, includeAuth: Boolean): String = buildString {
        val lines = buildList {
            if (summary.isNotBlank()) {
                add(summary)
                add("")
            }
            if (description.isNotBlank()) add(description)

            if (includeAuth) {
                add("")
                add(
                    "**Auth Requirements:** ${
                        auth.naturalLanguage(true).replace("[", "[[").replace("]", "]]")
                    }"
                )
            }
        }

        appendIdtLine(depth + 1, "/**")
        for (line in lines) appendIdtLine(depth + 1, " * $line")
        appendIdtLine(depth + 1, " * */")
    }
}