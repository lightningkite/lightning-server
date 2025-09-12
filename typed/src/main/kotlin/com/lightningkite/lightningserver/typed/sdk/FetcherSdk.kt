package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.plus
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.LiveVersion
import com.lightningkite.lightningserver.typed.sdk.SDK.sdk
import com.lightningkite.lightningserver.typed.sdk.SDK.processToModules
import com.lightningkite.services.data.KFile
import kotlin.reflect.KClass

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

    private fun SDK.Module.appendImportsTo(buffer: Appendable) {
        fun SDK.Module.imports(): List<String> =
            extendsInterfaces.flatMap { it.imports } + children.flatMap { it.imports() }

        val always = listOf(
            "com.lightningkite.lightningserver.HttpMethod",
            "com.lightningkite.lightningserver.typed.Fetcher",
            "kotlinx.serialization.builtins.serializer",
        )

        (always + imports())
            .distinct()
            .joinTo(buffer, "\n", prefix = "\n", postfix = "\n") { "import $it" }
    }

    private fun Appendable.writeInterface(module: SDK.Module, packageName: String) {
        appendLine("package $packageName")

        module.appendImportsTo(this)

        fun SDK.Module.writeInterface(depth: Int) {

            val extends = extendsInterfaces.filterSupertypes()
            val singleInterface = extends.singleOrNull()?.takeIf { declaredFunctions.isEmpty() && depth > 0 }

            appendLine()

            if (singleInterface == null) {
                appendDepth(depth, "interface ${info.interfaceName}" + (if (extends.isEmpty()) "" else " : ${extends.joinToString { it.kotlinString() }}") + " {")

                for (function in declaredFunctions) appendDepth(depth + 1, function.kotlinString())

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

        module.appendImportsTo(this)

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
                ?.let { it.qualifiedName ?: it.simpleName ?: throw SDK.GenerationException("No qualified name found for live version of $name: $it") }
                ?: throw SDK.GenerationException("FetcherSdk.Live annotation required on client interfaces, please provide a live version of $name.")

            return "$live(fetcher, ${path.toCodeString()}, ${typeParameters.joinToString { it.kotlinSerializer() }})"
        }

        fun SDK.Module.writeLive(chain: List<SDK.Module>) {
            val depth = chain.size

            val pathPrefix = (chain + this).fold(PathSpec.root) { acc, mod -> acc + mod.path }
            fun PathSpec.absolute(): PathSpec = pathPrefix + this

            val singleInterface = extendsInterfaces.singleOrNull()?.takeIf { declaredFunctions.isEmpty() && depth > 0 }

            appendLine()

            if (singleInterface == null) {
                val extendsInterfaces = listOf((chain + this).joinToString(".") { it.info.interfaceName }) + extendsInterfaces.filterSupertypes().map { inter ->
                    "${inter.kotlinString()} by ${inter.liveString(pathPrefix)}"
                }

                if (depth == 0) appendLine("class Live${info.interfaceName}(val fetcher: Fetcher) : ${extendsInterfaces.joinToString()} {")
                else appendDepth(depth, "inner class Live${info.interfaceName} : ${extendsInterfaces.joinToString()} {")

                for (function in declaredFunctions) {
                    appendDepth(depth + 1, "override ${function.kotlinString()} =")
                    appendDepth(
                        depth + 2,
                        when (function) {
                            is SDK.Function.Endpoint -> "fetcher(" + listOf(
                                function.path.absolute().toCodeString(),
                                "HttpMethod.${function.endpoint.method}",
                                function.input.kotlinSerializer(),
                                if (function.input.isUnit()) "kotlin.Unit" else "input",
                                function.output.kotlinSerializer()
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

            if (depth > 0) appendDepth(depth, "override val ${info.valueName} = ${singleInterface?.liveString(pathPrefix) ?: "Live${info.interfaceName}()"}")
        }

        module.writeLive(emptyList())
    }

    private fun List<InterfaceInfo>.filterSupertypes(): List<InterfaceInfo> {
        val supertypes = flatMap { it.type.supertypes }.mapNotNull { it.classifier as? KClass<*> }
        return filter { it.type !in supertypes }
    }

    private fun SDK.Function.kotlinString(): String {
        val argString = arguments.joinToString { "${it.name}: ${it.type.kotlinTypeString()}" }
        return when (this) {
            is SDK.Function.Endpoint ->
                "suspend fun $name($argString)" + if (output.isUnit()) "" else ": ${output.kotlinTypeString()}"

            is SDK.Function.Websocket ->
                "fun $name($argString): TypedWebSocket<${inputType.kotlinTypeString()}, ${outputType.kotlinTypeString()}>"
        }
    }
}