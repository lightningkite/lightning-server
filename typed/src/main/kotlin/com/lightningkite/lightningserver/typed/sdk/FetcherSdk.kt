package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.plus
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.LiveVersion
import com.lightningkite.lightningserver.typed.sdk.SDK.sdk
import com.lightningkite.lightningserver.typed.sdk.SDK.processToModules
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.defaultInfo
import com.lightningkite.services.data.KFile
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.nullable
import kotlin.reflect.KClass
import kotlin.reflect.KType

public object FetcherSdk : SDK.Format {
    context(server: ServerRuntime)
    override fun write(data: ServerDefinition, folder: KFile, packageName: String) {
        val processed = data.sdk().processToModules().ensureUniqueNames()

        folder.then("Api.kt").overwrite { writeInterface(processed, packageName) }
        folder.then("LiveApi.kt").overwrite { writeLive(processed, packageName) }
    }

    private fun KFile.overwrite(action: Appendable.() -> Unit) {
        parent?.createDirectories()
        sink().useAsAppendable(action)
    }

    private fun Appendable.appendDepth(depth: Int, value: CharSequence) {
        repeat(depth) { append('\t') }
        append(value)
        append('\n')
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
    private fun Appendable.writeInterface(module: SDK.Module, packageName: String) {
        appendLine("package $packageName")

        module.appendImports()

        fun SDK.Module.writeInterface(depth: Int) {

            val singleInterface = extendsInterfaces.singleOrNull()?.item?.takeIf { declaredFunctions.isEmpty() && children.isEmpty() && depth > 0 }

            appendLine()

            if (singleInterface == null) {
                appendDepth(depth, "interface ${info.interfaceName}" + (if (extendsInterfaces.isEmpty()) "" else " : ${extendsInterfaces.joinToString { it.item.kotlinString() }}") + " {")

                if (depth == 0) appendDepth(1, "fun withHeaderCalculator(calculator: suspend () -> List<Pair<String, String>>): ${info.interfaceName}")

                for (function in declaredFunctions) {
                    val docs = buildList {
                        fun line(string: String) { add(string); add("") }
                        if (function.summary.isNotBlank()) line(function.summary)
                        if (function.description.isNotBlank()) line(function.description)

                        add("**Auth Requirements:** ${function.auth.docString().replace("[", "[[").replace("]", "]]")}")
                    }

                    appendDepth(depth + 1, "/**")
                    for (line in docs) appendDepth(depth + 1, " * $line")
                    appendDepth(depth + 1, " * */")

                    appendDepth(depth + 1, function.kotlinString())
                }

                for (module in children) module.writeInterface(depth + 1)

                appendDepth(depth, "}")
            }

            if (depth > 0) appendDepth(depth, "val ${info.valueName}: ${singleInterface?.kotlinString() ?: (info.interfaceName)}")
        }

        module.writeInterface(0)
    }

    context(server: ServerRuntime)
    private fun Appendable.writeLive(module: SDK.Module, packageName: String) {
        appendLine("package $packageName")

        module.appendImports(
            "com.lightningkite.lightningserver.HttpMethod",
            "com.lightningkite.lightningserver.typed.Fetcher",
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

        fun SDK.Module.writeLive(chain: List<SDK.Module>) {
            val depth = chain.size

            val pathPrefix = (chain + this).fold(PathSpec.root) { acc, mod -> acc + mod.path }
            fun PathSpec.absolute(): PathSpec = pathPrefix + this

            val singleInterface = extendsInterfaces.singleOrNull()?.takeIf { declaredFunctions.isEmpty() && depth > 0 }

            appendLine()

            if (singleInterface == null) {
                val extendsInterfaces = listOf((chain + this).joinToString(".") { it.info.interfaceName }) + extendsInterfaces.map { inter ->
                    "${inter.item.kotlinString()} by ${inter.item.liveString(pathPrefix + inter.location)}"
                }

                if (depth == 0) {
                    appendLine("class Live${info.interfaceName}(val fetcher: Fetcher) : ${extendsInterfaces.joinToString()} {")

                    appendDepth(1, "override fun withHeaderCalculator(calculator: suspend () -> List<Pair<String, String>>): Live${info.interfaceName} = ")
                    appendDepth(2, "Live${info.interfaceName}(fetcher.withHeaderCalculator(calculator))")
                }
                else appendDepth(depth, "inner class Live${info.interfaceName} : ${extendsInterfaces.joinToString()} {")

                for (function in declaredFunctions) {
                    appendDepth(depth + 1, "override ${function.kotlinString()} =")
                    appendDepth(
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

                for (module in children) module.writeLive(chain + this)

                appendDepth(depth, "}")
            }

            if (depth > 0) appendDepth(depth, "override val ${info.valueName} = ${singleInterface?.item?.liveString(pathPrefix) ?: "Live${info.interfaceName}()"}")
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

    context(_: ServerRuntime)
    private fun AuthRequirement<*>.docString(): String = when (this) {
        is AuthRequirement.Options -> options.joinToString(" *or* ") { it.docString() }
        is AuthRequirement.AuthSetting -> setting()?.let { "$this (${it.docString()})" } ?: this.toString()
        else -> this.toString()
    }
}