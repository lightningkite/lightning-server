package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.typed.functionName
import com.lightningkite.services.data.KFile

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

    context(traverser: SdkServerDefinition.Traverser)
    private fun <T> SdkServerDefinition.Node.recurrenceOf(value: (SdkModuleInfo) -> T): Int = traverser.siblings
        .filter { value(it.module.info) == value(this.module.info) }
        .takeUnless { it.isEmpty() }
        ?.map { it.absolutePath.toString() }
        ?.plus(absolutePath.toString())
        ?.sorted()
        ?.indexOf(absolutePath.toString())
        ?: 0

    public fun Appendable.writeInterface(data: SdkServerDefinition, packageName: String) {
        appendLine("package $packageName")

        data.asSequence()
            .flatMap { it.module.endpoints.keys.filterNotNull() }
            .flatMap { it.imports }
            .distinct()
            .toList()
            .takeUnless { it.isEmpty() }
            ?.joinTo(this, "\n", prefix = "\n", postfix = "\n") { "import $it" }

        data.traverse { node ->
            val module = node.module
            val depth = node.depth

            val extends = module.endpoints.keys.filterNotNull()

            val declaredFunctions = module.endpoints[null] // null is top-level endpoints, not part of an interface
                ?.asSequence()
                ?.flatMap { (path, endpoints) ->
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
                }
                ?.run { // making sure duplicate functions have distinct names
                    val seen = HashMap<String, Int>()
                    map {
                        val count = seen[it]

                        if (count == null) {
                            seen[it] = 1
                            return@map it
                        }

                        seen[it] = count + 1
                        it.substringBefore('(') + (count + 1) + '(' + it.substringAfter('(')
                    }
                }
                ?.toList()
                ?: emptyList()

            val isSingleInterface = declaredFunctions.isEmpty() && extends.size == 1

            if (!isSingleInterface) {
                appendLine()

                val extendsInterfaces = extends
                    .distinct()
                    .takeUnless { it.isEmpty() }
                    ?.joinToString(prefix = ": ") { it.kotlinString() }
                    ?: ""

                val interfaceName = module.info.interfaceName + (node.recurrenceOf { it.interfaceName }.takeUnless { it == 0 }?.plus(1) ?: "")

                appendDepth(depth, "interface $interfaceName$extendsInterfaces {")

                for (func in declaredFunctions) appendDepth(depth + 1, func)

                traverseChildrenRecursively()

                appendDepth(depth, "}")
            }

            if (depth > 0) {
                val valueName = module.info.valueName + (node.recurrenceOf { it.valueName }.takeUnless { it == 0 }?.plus(1) ?: "")
                appendDepth(depth,
                    "val $valueName: ${if (isSingleInterface) extends.first().kotlinString() else module.info.interfaceName}"
                )
            }
        }
    }
}