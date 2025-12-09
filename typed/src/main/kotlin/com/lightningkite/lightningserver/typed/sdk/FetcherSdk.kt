package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.auth.naturalLanguage
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.plus
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.LiveVersion
import com.lightningkite.lightningserver.typed.sdk.SDK.processToModules
import com.lightningkite.lightningserver.typed.sdk.SDK.sdk
import com.lightningkite.services.data.KFile

public class FetcherSdk(
    public val packageName: String,
    public val rootInfo: SdkModule.Info = SdkModule.Info("Api"),
    public val interfaceFilename: String = "${rootInfo.interfaceName}.kt",
    public val liveFilename: String = "Live${rootInfo.interfaceName}.kt",
    public val includeDocComments: Boolean = true,
) : SDK.Format {

    context(server: ServerRuntime)
    override fun write(folder: KFile) {
        val processed = server.server.sdk(rootInfo).processToModules().ensureUniqueNames()

        folder.then(interfaceFilename).overwrite { writeInterface(processed) }
        folder.then(liveFilename).overwrite { writeLive(processed) }
    }

    context(buffer: Appendable)
    private fun SDK.Module.appendImports(
        vararg imports: String
    ) {
        fun SDK.Module.imports(): List<String> =
            extendsInterfaces.flatMap { it.item.imports } + children.flatMap { it.imports() }

        (imports.toList() + imports())
            .distinct()
            .joinTo(buffer, "\n", prefix = "\n", postfix = "\n") { "import $it" }
    }

    context(_: ServerRuntime)
    private fun Appendable.writeInterface(module: SDK.Module) {
        appendLine("package $packageName")

        module.appendImports()

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
        appendLine("package $packageName")

        module.appendImports(
            "com.lightningkite.lightningserver.HttpMethod",
            "com.lightningkite.lightningserver.typed.Fetcher",
            "kotlinx.serialization.ContextualSerializer",
            "kotlinx.serialization.builtins.serializer",
            "kotlinx.serialization.builtins.MapSerializer",
            "kotlinx.serialization.builtins.ListSerializer",
            "kotlinx.serialization.builtins.nullable"
        )

        fun PathSpec.toCodeString() = segments.joinToString("/", prefix = "\"", postfix = "\"") {
            when (it) {
                is PathSpec.Segment.Constant -> it.value
                is PathSpec.Segment.Wildcard<*> -> $$"${fetcher.url($${it.name}, $${it.serializer.kotlinSerializer()})}"
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
        val argString = arguments.joinToString { "${it.name}: ${it.type.kotlinTypeString()}" }
        return when (this) {
            is SDK.Function.Endpoint ->
                "suspend fun $functionName($argString)" + if (outputType.isUnit()) "" else ": ${outputType.kotlinTypeString()}"

            is SDK.Function.Websocket ->
                "fun $functionName($argString): ClientWebSocket<${inputType.kotlinTypeString()}, ${outputType.kotlinTypeString()}>"
        }
    }
}