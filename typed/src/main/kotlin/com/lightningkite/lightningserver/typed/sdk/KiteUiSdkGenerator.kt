package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.ApiWebsocketHandler
import com.lightningkite.lightningserver.typed.DocGroupExtension
import com.lightningkite.lightningserver.typed.Documentable
import com.lightningkite.lightningserver.typed.apiHttpHandlers
import com.lightningkite.lightningserver.typed.apiWebsocketHandlers
import com.lightningkite.lightningserver.typed.docGroup
import com.lightningkite.lightningserver.typed.functionName
import com.lightningkite.lightningserver.typed.interfaces
import com.lightningkite.lightningserver.typed.location
import com.lightningkite.services.database.ConditionSerializer
import com.lightningkite.services.database.DataClassPathSerializer
import com.lightningkite.services.database.ModificationSerializer
import com.lightningkite.services.database.PartialSerializer
import com.lightningkite.services.database.SortPartSerializer
import com.lightningkite.services.database.listElement
import com.lightningkite.services.database.mapValueElement
import com.lightningkite.services.database.nullElement
import com.lightningkite.services.database.serializableProperties
import com.lightningkite.services.database.typeParametersSerializersOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.capturedKClass
import kotlinx.serialization.internal.GeneratedSerializer
import java.io.File
import java.util.Locale

// Let's wrap the old fugly code.
public fun ServerDefinition.writeKiteUiSdk(packageName: String, folder: File): Unit = with(KiteUiSdkGenerator) {
    println(extensions[DocGroupExtension]?.entries?.joinToString("\n") { it.toString() })
    apiHttpHandlers.forEach {
        println("${it.summary}: ${location(it)} (${location(it).path.docGroup})")
    }
//    endpointsByGroup.forEach {
//        println("${it.key}: ${it.value.joinToString(){ location(it).toString() }}")
//    }
    folder.mkdirs()
    folder.resolve("Api.kt").writer().use { it.writeInterface(packageName) }
    folder.resolve("LiveApi.kt").writer().use { it.writeLive(packageName) }
    folder.resolve("CachedApi.kt").writer().use { it.writeCached(packageName) }
}

public class SDKException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

@OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
private object KiteUiSdkGenerator {

    private fun KSerializer<*>.subSerializers(): Array<KSerializer<*>> = nullElement()?.let { arrayOf(it) }
        ?: listElement()?.let { arrayOf(it) }
        ?: mapValueElement()?.let { arrayOf(it) }
        ?: (this as? GeneratedSerializer<*>)?.typeParametersSerializers()
        ?: (this as? ConditionSerializer<*>)?.inner?.let { arrayOf(it) }
        ?: (this as? ModificationSerializer<*>)?.inner?.let { arrayOf(it) }
        ?: (this as? PartialSerializer<*>)?.source?.let { arrayOf(it) }
        ?: (this as? SortPartSerializer<*>)?.inner?.let { arrayOf(it) }
        ?: (this as? DataClassPathSerializer<*>)?.inner?.let { arrayOf(it) }
        ?: arrayOf()

    private fun KSerializer<*>.subAndChildSerializers(): Array<KSerializer<*>> = nullElement()?.let { arrayOf(it) }
        ?: serializableProperties?.map { it.serializer }?.toTypedArray()
        ?: listElement()?.let { arrayOf(it) }
        ?: mapValueElement()?.let { arrayOf(it) }
        ?: (this as? GeneratedSerializer<*>)?.run { childSerializers() + typeParametersSerializers() }
        ?: (this as? ConditionSerializer<*>)?.inner?.let { arrayOf(it) }
        ?: (this as? ModificationSerializer<*>)?.inner?.let { arrayOf(it) }
        ?: (this as? PartialSerializer<*>)?.source?.let { arrayOf(it) }
        ?: (this as? SortPartSerializer<*>)?.inner?.let { arrayOf(it) }
        ?: (this as? DataClassPathSerializer<*>)?.inner?.let { arrayOf(it) }
        ?: arrayOf()

//    context(definition: ServerDefinition)
    private fun KSerializer<*>.kotlinTypeString(): String {
        return when (this.descriptor.kind) {
            StructureKind.MAP -> "Map<String, ${this.mapValueElement()!!.kotlinTypeString()}>"

            StructureKind.LIST -> "List<${this.listElement()!!.kotlinTypeString()}>"
            SerialKind.CONTEXTUAL -> descriptor.capturedKClass!!.qualifiedName!!
            else -> {
                descriptor.serialName
                    .substringBefore('/')
                    .substringBefore('<') + (typeParametersSerializersOrNull()?.takeUnless { it.isEmpty() }
                    ?.joinToString(", ", "<", ">") { it.kotlinTypeString() } ?: "")
            }
        }
    }

    context(definition: ServerDefinition)
    private fun KSerializer<*>.kotlinSerializer(): String {
        nullElement()?.let { return it.kotlinSerializer() + ".nullable" }
        return when (this.descriptor.kind) {
            StructureKind.MAP -> "MapSerializer(String.serializer(), ${
                this.mapValueElement()!!.kotlinSerializer()
            })"

            StructureKind.LIST -> "ListSerializer(${this.listElement()!!.kotlinSerializer()})"
            SerialKind.CONTEXTUAL -> "ContextualSerializer(${kotlinTypeString()}::class, null, arrayOf())"

            else -> {
                descriptor.serialName
                    .substringBefore('/')
                    .substringBefore('<')
                    .plus(".serializer")
                    .plus(typeParametersSerializersOrNull()?.joinToString(", ", "(", ")") { it.kotlinSerializer() }
                        ?: "()")
            }
        }
    }

    context(definition: ServerDefinition)
    private fun PathSpec.toKotlinArgsStrings() = this.wildcards.map {
        it.name + ": " + it.serializer.kotlinTypeString()
    }

    context(definition: ServerDefinition)
    private val Documentable.docGroupIdentifier: String?
        get() = when(this) {
            is ApiWebsocketHandler<*, *, *, *, *> -> definition.location(this)
            is ApiHttpHandler<*, *, *, *> -> definition.location(this).path
            else -> null
        }?.let { with(definition) { it.docGroup } }
            ?.replace(Regex("""[^0-9a-zA-Z]+(?<following>.)?""")) { match ->
                match.groups["following"]?.value?.uppercase() ?: ""
            }
            ?.replaceFirstChar { it.lowercase() }

    context(definition: ServerDefinition)
    val renderableEndpoints get() = (definition.apiHttpHandlers
        .filter { definition.location(it).method != HttpMethod.GET || it.inputType == Unit.serializer() } + definition.apiWebsocketHandlers)
        .distinctBy { it.docGroupIdentifier.toString() + "/" + it.summary }
    context(definition: ServerDefinition)
    val endpointsByGroup get() = renderableEndpoints.groupBy { it.docGroupIdentifier }
        .mapValues { it.value.sortedBy { it.functionName } }
        .entries
        .sortedBy { it.key ?: "" }

    context(definition: ServerDefinition)
    fun Appendable.writeInterface(packageName: String) {
        appendLine("package $packageName")
        appendLine()
        listOf(
            "com.lightningkite.*",
            "com.lightningkite.lightningdb.*",
            "com.lightningkite.kiteui.*",
            "kotlinx.datetime.*",
            "com.lightningkite.serialization.*",
            "com.lightningkite.lightningserver.db.*",
            "com.lightningkite.lightningserver.auth.*",
        )
            .toSet()
            .joinTo(this, "\n") { "import $it" }
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
                        try {
                            it.fullyQualifiedName +
                                    (it.typeArguments.takeUnless { it.isEmpty() }
                                        ?.joinToString(", ", "<", ">") { it.kotlinTypeString() }
                                        ?: "")
                        } catch (e: Exception) {
                            throw SDKException("Failed to generate typing for ${it}", e)
                        }
                    })
                }
                appendLine("{")
            }
            endpoints.forEach {
                if (it.belongsToInterface != null) return@forEach
                when (it) {
                    is ApiHttpHandler<*, *, *, *> -> {
                        try {
                            val args =
                                definition.location(it).path.toKotlinArgsStrings() +
                                        if (it.inputType.descriptor.serialName == "kotlin.Unit") emptyList()
                                        else listOf("input: ${it.inputType.kotlinTypeString()}")
                            appendLine(
                                "suspend fun ${it.functionName}(${args.joinToString()}): ${it.outputType.kotlinTypeString()}"
                            )
                        } catch (e: Exception) {
                            throw SDKException("Failed to render endpoint interface ${definition.location(it).path}", e)
                        }
                    }

                    is ApiWebsocketHandler<*, *, *, *, *> -> {
                        try {
                            append(
                                "fun ${it.functionName}(${definition.location(it).toKotlinArgsStrings().joinToString()}): "
                            )
                            append("TypedWebSocket<")
                            append(it.inputType.kotlinTypeString())
                            append(", ")
                            append(it.outputType.kotlinTypeString())
                            appendLine(">")
                        } catch (e: Exception) {
                            throw SDKException("Failed to render websocket interface ${definition.location(it)}", e)
                        }
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

    private fun PathSpec.toCodeString() = "\"${segments.joinToString("/") { it.toCodeString() }}\""
    private fun PathSpec.Segment.toCodeString() = when (this) {
        is PathSpec.Segment.Constant -> this.value
        is PathSpec.Segment.Wildcard<*> -> "${'$'}{${this.name}.urlifyToCommaString()}"
    }

    context(definition: ServerDefinition)
    fun Appendable.writeLive(packageName: String) {
        appendLine("package $packageName")
        appendLine()
        listOf(
            "com.lightningkite.*",
            "com.lightningkite.lightningdb.*",
            "com.lightningkite.kiteui.*",
            "kotlinx.datetime.*",
            "com.lightningkite.serialization.*",
            "com.lightningkite.lightningserver.db.*",
            "com.lightningkite.lightningserver.auth.*",
            "com.lightningkite.lightningserver.networking.Fetcher",
            "kotlinx.serialization.builtins.*",
            "kotlinx.serialization.*",
        )
            .toSet()
            .joinTo(this, "\n") { "import $it" }
        appendLine()

        appendLine("class LiveApi2(val fetcher: Fetcher): Api2 {")
        appendLine("override fun withHeaderCalculator(headerCalculator: suspend () -> List<Pair<String, String>>): LiveApi2 = LiveApi2(fetcher.withHeaderCalculator(headerCalculator))")
        val interfacePathMap = definition.interfaces.associate { it.item to it.location }
        endpointsByGroup.forEach { (group, endpoints) ->
            val interfaces = endpoints.mapNotNull { it.belongsToInterface }.distinct()
            val iname =
                "Api2${group?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }}"
            if (group != null) {
                append("inner class ${iname}Live ")
                interfaces
                    .map {
                        val path = interfacePathMap[it] ?: throw IllegalStateException("Interface $it does not have a path.")
                        try {
                            it.fullyQualifiedName +
                                    (it.typeArguments.takeUnless { it.isEmpty() }
                                        ?.joinToString(", ", "<", ">") { it.kotlinTypeString() }
                                        ?: "") + " by " + it.fullyQualifiedName + "Live(fetcher, ${path.toCodeString()}, ${it.typeArguments.joinToString() { it.kotlinSerializer() }})"
                        } catch (e: Exception) {
                            throw SDKException("Failed to generate typing for ${path}", e)
                        }
                    }
                    .plus("Api2.$iname")
                    .let {
                        append(": ")
                        append(it.joinToString(", "))
                    }
                appendLine("{")
            }
            endpoints.forEach {
                if (it.belongsToInterface != null) return@forEach
                when (it) {
                    is ApiHttpHandler<*, *, *, *> -> {
                        try {
                            val args =
                                definition.location(it).path.toKotlinArgsStrings() +
                                        if (it.inputType.descriptor.serialName == "kotlin.Unit") emptyList()
                                        else listOf("input: ${it.inputType.kotlinTypeString()}")
                            appendLine(
                                "override suspend fun ${it.functionName}(${args.joinToString()}): ${it.outputType.kotlinTypeString()}"
                            )
                            append("    = fetcher(")
                            append(definition.location(it).path.toCodeString())
                            append(", HttpMethod.")
                            append(definition.location(it).method.toString().uppercase())
                            append(", ")
                            append(it.inputType.kotlinSerializer())
                            append(", ${if (it.inputType.descriptor.serialName == "kotlin.Unit") "Unit" else "input"}, ")
                            append(it.outputType.kotlinSerializer())
                            appendLine(")")
                        } catch (e: Exception) {
                            throw SDKException("Failed to render live endpoint ${definition.location(it)}", e)
                        }
                    }

                    is ApiWebsocketHandler<*, *, *, *, *> -> {
                        try {
                            append(
                                "override fun ${it.functionName}(${definition.location(it).toKotlinArgsStrings().joinToString()}): "
                            )
                            append("TypedWebSocket<")
                            append(it.inputType.kotlinTypeString())
                            append(", ")
                            append(it.outputType.kotlinTypeString())
                            appendLine(">")
                            append("    = fetcher.websocket(")
                            append(definition.location(it).toCodeString())
                            append(", ")
                            append(it.inputType.kotlinSerializer())
                            append(", ")
                            append(it.outputType.kotlinSerializer())
                            appendLine(")")
                        } catch (e: Exception) {
                            throw SDKException("Failed to render live websocket ${definition.location(it)}", e)
                        }
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

    context(definition: ServerDefinition)
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
        appendLine("open class CachedApi2(val uncached: Api2) {")
        endpointsByGroup.forEach { (group, endpoints) ->
            for (inter in endpoints.mapNotNull { it.belongsToInterface }.distinct()) {
                if (inter.fullyQualifiedName == "ClientModelRestEndpoints") {
                    try {
                        append("val ${group}: ModelCache")
                        append(
                            inter.typeArguments
                                .takeUnless { it.isEmpty() }
                                ?.joinToString(", ", "<", ">") { it.kotlinTypeString() }
                                ?: ""
                        )
                        append(" = ModelCache(uncached.")
                        append(group)
                        append(", ${inter.typeArguments[0].kotlinSerializer()})")
                        appendLine()
                    } catch (e: Exception) {
                        throw SDKException("Failed to render ModelCache for $group", e)
                    }
                }
            }
        }
        appendLine("}")
    }
}