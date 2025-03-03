package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.http.Http
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.websocket.WebSockets
import com.lightningkite.serialization.listElement
import com.lightningkite.serialization.mapValueElement
import com.lightningkite.serialization.tryTypeParameterSerializers2
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
                    .substringBefore('<') + (tryTypeParameterSerializers2()?.takeUnless { it.isEmpty() }
                    ?.joinToString(", ", "<", ">") { it.kotlinTypeString() } ?: "")
            }
        }
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
        appendLine()

        appendLine("interface Api2 {")
        appendLine("fun accessToken(accessToken: String): Api2")
        appendLine("fun masquerade(string: String): Api2")
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
                                it.subtypes.takeUnless { it.isEmpty() }?.joinToString(", ", "<", ">") {
                                    it.kotlinTypeString()
                                }
                    })
                }
                appendLine("{")
            }
            endpoints.forEach {
                if (it.belongsToInterface != null) return@forEach
                when (it) {
                    is ApiEndpoint<*, *, *, *> -> {
                        appendLine(
                            "suspend fun ${it.functionName}(input: ${
                                it.inputType.kotlinTypeString()
                            }): ${it.outputType.kotlinTypeString()}"
                        )
                    }

                    is ApiWebsocket<*, *, *, *, *> -> {
                        append(
                            "suspend fun ${it.functionName}(input: ${
                                it.inputType.kotlinTypeString()
                            }): "
                        )
                        append("TypedWebSocket<")
                        append(it.inputType.kotlinTypeString())
                        append(", ")
                        append(it.outputType.kotlinTypeString())
                        append(">")
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

    fun Appendable.writeLive(packageName: String) {
        appendLine("package $packageName")
        appendLine()
        appendLine("import com.lightningkite.*")
        appendLine("import com.lightningkite.lightningdb.*")
        appendLine("import com.lightningkite.kiteui.*")
        appendLine("import kotlinx.datetime.*")
        appendLine("import com.lightningkite.serialization.*")
        appendLine("import com.lightningkite.lightningserver.db.*")
        appendLine()

        appendLine("interface LiveApi2: Api2 {")
        appendLine("fun accessToken(accessToken: String): Api2")
        appendLine("fun masquerade(string: String): Api2")
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
                                it.subtypes.takeUnless { it.isEmpty() }?.joinToString(", ", "<", ">") {
                                    it.kotlinTypeString()
                                }
                    })
                }
                appendLine("{")
            }
            endpoints.forEach {
                if (it.belongsToInterface != null) return@forEach
                when (it) {
                    is ApiEndpoint<*, *, *, *> -> {
                        appendLine(
                            "suspend fun ${it.functionName}(input: ${
                                it.inputType.kotlinTypeString()
                            }): ${it.outputType.kotlinTypeString()}"
                        )
                    }

                    is ApiWebsocket<*, *, *, *, *> -> {
                        append(
                            "suspend fun ${it.functionName}(input: ${
                                it.inputType.kotlinTypeString()
                            }): "
                        )
                        append("TypedWebSocket<")
                        append(it.inputType.kotlinTypeString())
                        append(", ")
                        append(it.outputType.kotlinTypeString())
                        append(">")
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
}