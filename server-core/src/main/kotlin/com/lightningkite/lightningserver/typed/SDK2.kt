package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.Http
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.websocket.WebSockets
import com.lightningkite.serialization.listElement
import com.lightningkite.serialization.mapValueElement
import com.lightningkite.serialization.tryTypeParameterSerializers2
import com.lightningkite.serialization.tryTypeParameterSerializers3
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import java.io.File
import java.util.Locale
import kotlin.collections.component1
import kotlin.collections.component2

object SDK2 {

    val renderableEndpoints = (Http.endpoints.values.filterIsInstance<ApiEndpoint<*, *, *, *>>()
        .filter { it.route.method != HttpMethod.GET || it.inputType == Unit.serializer() } + WebSockets.handlers.values.filterIsInstance<ApiWebsocket<*, *, *, *, *>>())
        .distinctBy { it.docGroupIdentifier.toString() + "/" + it.summary }
    val endpointsByGroup = renderableEndpoints.groupBy { it.docGroupIdentifier }
        .entries
        .sortedBy { it.key ?: "" }

    fun write(packageName: String, folder: File) {
        folder.mkdirs()

        folder.resolve("Api.kt").writer().use { it.writeInterface(packageName) }
    }

    private fun KSerializer<*>.kotlinTypeString(): String {
        return when (this.descriptor.kind) {
            StructureKind.MAP -> "Map<String, ${
                this.mapValueElement()!!.kotlinTypeString()
            }>"

            StructureKind.LIST -> "List<${this.listElement()!!.kotlinTypeString()}>"
            SerialKind.CONTEXTUAL -> this.uncontextualize().kotlinTypeString()
            else -> {
                descriptor.serialName
                    .substringBefore('/')
                    .substringBefore('<') + (tryTypeParameterSerializers3()?.takeUnless { it.isEmpty() }
                    ?.joinToString(", ", "<", ">") { it.kotlinTypeString() } ?: "")
            }
        }
    }
    private fun KSerializer<*>.kotlinSerializer(): String {
        return when (this.descriptor.kind) {
            StructureKind.MAP -> "MapSerializer(String.serializer(), ${
                this.mapValueElement()!!.kotlinSerializer()
            })"

            StructureKind.LIST -> "ListSerializer(${this.listElement()!!.kotlinSerializer()})"
            SerialKind.CONTEXTUAL -> "ContextualSerializer(${kotlinTypeString()}::class, null, arrayOf(${
                this.uncontextualize().tryTypeParameterSerializers3()?.joinToString(", ") { it.kotlinSerializer() } ?: ""
            }))"
            else -> {
                descriptor.serialName
                    .substringBefore('/')
                    .substringBefore('<')
                    .plus(".serializer")
                    .plus(tryTypeParameterSerializers3()?.joinToString(", ", "(", ")") { it.kotlinSerializer() } ?: "()")
            }
        }
    }

    private fun TypedServerPath.toKotlinArgsStrings() = this.parameters.map {
        it.name + ": " + it.serializer.kotlinTypeString()
    }

    fun Appendable.writeInterface(packageName: String) {
        appendLine("package $packageName")
        appendLine()
        appendLine("import com.lightningkite.*")
        appendLine("import com.lightningkite.lightningdb.*")
        appendLine("import com.lightningkite.kiteui.*")
        appendLine("import kotlinx.datetime.*")
        appendLine("import com.lightningkite.serialization.*")
        appendLine("import com.lightningkite.lightningserver.db.*")
        appendLine("import com.lightningkite.lightningserver.auth.*")
        appendLine()

        appendLine("interface Api2 {")
        appendLine("fun withHeaderCalculator(headerCalculator: suspend () -> List<Pair<String, String>>): Api2")
        endpointsByGroup.forEach { (group, endpoints) ->
            val interfaces = endpoints.mapNotNull { it.belongsToInterface }.distinct()
            val iname =
                "Api2${group?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }}"
            if (group != null) {
                append("interface $iname ")
                interfaces.takeUnless { it.isEmpty() }?.let {
                    append(": ")
                    append(it.joinToString(", ") {
                        it.name +
                                (it.subtypes.takeUnless { it.isEmpty() }?.joinToString(", ", "<", ">") {
                                    it.kotlinTypeString()
                                } ?: "")
                    })
                }
                appendLine("{")
            }
            endpoints.forEach {
                if (it.belongsToInterface != null) return@forEach
                when (it) {
                    is ApiEndpoint<*, *, *, *> -> {
                        val args =
                            it.path.toKotlinArgsStrings() +
                                    if (it.inputType.descriptor.serialName == "kotlin.Unit") emptyList()
                                    else listOf("input: ${it.inputType.kotlinTypeString()}")
                        appendLine(
                            "suspend fun ${it.functionName}(${args.joinToString()}): ${it.outputType.kotlinTypeString()}"
                        )
                    }

                    is ApiWebsocket<*, *, *, *, *> -> {
                        append(
                            "fun ${it.functionName}(${it.path.toKotlinArgsStrings().joinToString()}): "
                        )
                        append("TypedWebSocket<")
                        append(it.inputType.kotlinTypeString())
                        append(", ")
                        append(it.outputType.kotlinTypeString())
                        appendLine(">")
                    }

                    else -> throw IllegalStateException("Unknown endpoint type: $it")
                }
            }
            if (group != null) {
                appendLine("}")
                appendLine("val ${group}: $iname")
            }
        }
        appendLine("}")
    }

    private fun ServerPath.toCodeString() = "\"${segments.joinToString("/") { it.toCodeString() }}\""
    private fun ServerPath.Segment.toCodeString() = when (this) {
        is ServerPath.Segment.Constant -> this.value
        is ServerPath.Segment.Wildcard -> "${'$'}{${this.name}}"
    }

    fun Appendable.writeLive(packageName: String) {
        appendLine("package $packageName")
        appendLine()
        appendLine("import com.lightningkite.*")
        appendLine("import com.lightningkite.lightningdb.*")
        appendLine("import com.lightningkite.kiteui.*")
        appendLine("import kotlinx.datetime.*")
        appendLine("import com.lightningkite.serialization.*")
        appendLine("import com.lightningkite.lightningserver.db.*")
        appendLine("import com.lightningkite.lightningserver.auth.*")
        appendLine("import kotlinx.serialization.builtins.*")
        appendLine("import kotlinx.serialization.*")
        appendLine()

        appendLine("class LiveApi2(val fetcher: Fetcher): Api2 {")
        appendLine("override fun withHeaderCalculator(headerCalculator: suspend () -> List<Pair<String, String>>): LiveApi2 = LiveApi2(fetcher.withHeaderCalculator(headerCalculator))")
        endpointsByGroup.forEach { (group, endpoints) ->
            val interfaces = endpoints.mapNotNull { it.belongsToInterface }.distinct()
            val iname =
                "Api2${group?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }}"
            if (group != null) {
                append("inner class ${iname}Live ")
                interfaces.map{
                    it.name +
                            (it.subtypes.takeUnless { it.isEmpty() }?.joinToString(", ", "<", ">") {
                                it.kotlinTypeString()
                            } ?: "") + " by " + it.name + "Live(fetcher, ${it.path.toCodeString()}, ${it.subtypes.joinToString() { it.kotlinSerializer() }})"
                }.plus("Api2.$iname").let {
                    append(": ")
                    append(it.joinToString(", "))
                }
                appendLine("{")
            }
            endpoints.forEach {
                if (it.belongsToInterface != null) return@forEach
                when (it) {
                    is ApiEndpoint<*, *, *, *> -> {
                        val args =
                            it.path.toKotlinArgsStrings() +
                                    if (it.inputType.descriptor.serialName == "kotlin.Unit") emptyList()
                                    else listOf("input: ${it.inputType.kotlinTypeString()}")
                        appendLine(
                            "override suspend fun ${it.functionName}(${args.joinToString()}): ${it.outputType.kotlinTypeString()}"
                        )
                        append("    = fetcher(")
                        append(it.path.path.toCodeString())
                        append(", HttpMethod.")
                        append(it.route.method.toString().uppercase())
                        append(", ")
                        append(it.inputType.kotlinSerializer())
                        append(", ${if(it.inputType.descriptor.serialName == "kotlin.Unit") "Unit" else "input"}, ")
                        append(it.outputType.kotlinSerializer())
                        appendLine(")")
                    }

                    is ApiWebsocket<*, *, *, *, *> -> {
                        append(
                            "override fun ${it.functionName}(${it.path.toKotlinArgsStrings().joinToString()}): "
                        )
                        append("TypedWebSocket<")
                        append(it.inputType.kotlinTypeString())
                        append(", ")
                        append(it.outputType.kotlinTypeString())
                        appendLine(">")
                        append("    = fetcher.websocket(")
                        append(it.path.path.toCodeString())
                        append(", ")
                        append(it.inputType.kotlinSerializer())
                        append(", ")
                        append(it.outputType.kotlinSerializer())
                        appendLine(")")
                    }

                    else -> throw IllegalStateException("Unknown endpoint type: $it")
                }
            }
            if (group != null) {
                appendLine("}")
                appendLine("override val ${group}: ${iname}Live = ${iname}Live()")
            }
        }
        appendLine("}")
    }

    fun Appendable.writeCached(packageName: String) {
        appendLine("package $packageName")
        appendLine()
        appendLine("import com.lightningkite.*")
        appendLine("import com.lightningkite.lightningdb.*")
        appendLine("import com.lightningkite.kiteui.*")
        appendLine("import kotlinx.datetime.*")
        appendLine("import com.lightningkite.serialization.*")
        appendLine("import com.lightningkite.lightningserver.db.*")
        appendLine("import com.lightningkite.lightningserver.auth.*")
        appendLine("import kotlinx.serialization.builtins.*")
        appendLine("import kotlinx.serialization.*")
        appendLine()
        appendLine("class CachedApi2(val uncached: Api2) {")
        endpointsByGroup.forEach { (group, endpoints) ->
            for(inter in endpoints.mapNotNull { it.belongsToInterface }.distinct()) {
                if(inter.name == "ClientModelRestEndpoints") {
                    append("val ${group}: ModelCache")
                    append(inter.subtypes.takeUnless { it.isEmpty() }?.joinToString(", ", "<", ">") {
                        it.kotlinTypeString()
                    } ?: "")
                    append(" = ModelCache(uncached.")
                    append(group)
                    append(", ${inter.subtypes[0].kotlinSerializer()})")
                    appendLine()
                }
            }
        }
        appendLine("}")
    }
}