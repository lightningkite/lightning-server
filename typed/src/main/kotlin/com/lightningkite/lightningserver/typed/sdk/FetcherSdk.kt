package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.typed.functionName
import com.lightningkite.services.data.KFile
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer

public object FetcherSdk : SdkFormat {
    override fun write(data: SdkServerDefinition, folder: KFile, packageName: String) {
        folder.then("Api.kt").overwrite { writeInterface(data, packageName) }
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

    private fun Appendable.writeInterface(data: SdkServerDefinition, packageName: String) {
        appendLine("package $packageName")
        appendLine()

        data.asSequence()
            .flatMap { it.module.endpoints.keys.filterNotNull() }
            .flatMap { it.imports }
            .distinct()
            .joinTo(this, "\n") { "import $it" }

        appendLine()

        data.traverse { (depth, _, module) ->
            val extends = module.endpoints.keys.filterNotNull()

            val functions = module.endpoints[null]?.asSequence()?.flatMap { (path, endpoints) ->
                endpoints.http.map { (_, endpoint) ->
                    val args = path.wildcards
                        .map { "${it.name}: ${it.serializer.kotlinTypeString()}" }
                        .plus(
                            if (endpoint.inputType.isUnit()) emptyList()
                            else listOf("input: ${endpoint.inputType.kotlinTypeString()}")
                        )

                    "suspend fun ${endpoint.functionName}(${args.joinToString()})" +
                            if (endpoint.outputType.isUnit()) "" else ": ${endpoint.outputType.kotlinTypeString()}"
                }
            }?.toList() ?: emptyList()

            if (functions.isNotEmpty() || extends.size != 1) {
                appendLine()

                val interfaces = extends
                    .takeUnless { it.isEmpty() }
                    ?.joinToString(prefix = ": ") { it.kotlinString() }
                    ?: ""

                appendDepth(depth, "interface ${module.info.interfaceName}$interfaces {")

                for (func in functions) appendDepth(depth + 1, func)

                traverseChildren()

                appendDepth(depth, "}")
            }

            if (depth > 0) {
                appendDepth(depth, "val ${module.info.valueName}: ${if (extends.size == 1) extends.first().kotlinString() else module.info.interfaceName}")
            }
        }
    }
}