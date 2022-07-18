package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningdb.listElement
import com.lightningkite.lightningdb.mapValueElement
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.Http
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.websocket.WebSockets
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.reflect.KClass
import kotlin.reflect.KType

fun Documentable.Companion.kotlinSdk(packageName: String, stream: OutputStream) {
    ZipOutputStream(stream).use { zip ->
        zip.putNextEntry(ZipEntry("sdk/Api.kt"))
        zip.write(kotlinApi(packageName).toByteArray())
        zip.closeEntry()

        zip.putNextEntry(ZipEntry("sdk/Sessions.kt"))
        zip.write(kotlinSessions(packageName).toByteArray())
        zip.closeEntry()

        zip.putNextEntry(ZipEntry("sdk/LiveApi.kt"))
        zip.write(kotlinLiveApi(packageName).toByteArray())
        zip.closeEntry()
    }
}

fun Documentable.Companion.kotlinSdkLocal(packageName: String, root: File = File("../client/src/main/java")) {
    root.resolve("Api.kt").writeText(kotlinApi(packageName))
    root.resolve("Sessions.kt").writeText(kotlinSessions(packageName))
    root.resolve("LiveApi.kt").writeText(kotlinLiveApi(packageName))
}

fun Documentable.Companion.kotlinApi(packageName: String): String = CodeEmitter(packageName).apply {
    imports.add("io.reactivex.rxjava3.core.Single")
    imports.add("io.reactivex.rxjava3.core.Observable")
    imports.add("com.lightningkite.rx.okhttp.*")
    imports.add("com.lightningkite.lightningdb.*")
    imports.add("java.util.UUID")
    imports.add("java.util.Optional")
    imports.add("java.time.*")
    val byGroup = safeDocumentables
        .distinctBy { it.docGroup.toString() + "/" + it.summary }.groupBy { it.docGroup }
    val groups = byGroup.keys.filterNotNull()
    appendLine("interface Api {")
    for(group in groups) {
        appendLine("    val ${group.groupToPartName()}: ${group.groupToInterfaceName()}")
    }
    for(entry in byGroup[null] ?: listOf()) {
        append("    ")
        this.functionHeader(entry)
        appendLine()
    }
    for(group in groups) {
        appendLine("    interface ${group.groupToInterfaceName()} {")
        for(entry in byGroup[group]!!) {
            append("        ")
            this.functionHeader(entry)
            appendLine()
        }
        appendLine("    }")
    }
    appendLine("}")
    appendLine()
}.toString()

fun Documentable.Companion.kotlinSessions(packageName: String): String = CodeEmitter(packageName).apply {
    imports.add("io.reactivex.rxjava3.core.Single")
    imports.add("io.reactivex.rxjava3.core.Observable")
    imports.add("com.lightningkite.rx.okhttp.*")
    imports.add("com.lightningkite.lightningdb.*")
    imports.add("java.util.UUID")
    imports.add("java.util.Optional")
    imports.add("java.time.*")
    val byUserType = safeDocumentables.groupBy { it.authInfo.type }
    val userTypes = byUserType.keys.filterNotNull()
    userTypes.forEach { userType ->
        val byGroup = ((byUserType[userType] ?: listOf()) + (byUserType[null]?: listOf())).groupBy { it.docGroup }
        val groups = byGroup.keys.filterNotNull()
        val sessionClassName = "${userType.substringAfterLast('.')}Session"
        appendLine("abstract class Abstract$sessionClassName(api: Api, ${userType.userTypeTokenName()}: String) {")
        appendLine("    abstract val api: Api")
        appendLine("    abstract val ${userType.userTypeTokenName()}: String")
        for(group in groups) {
            appendLine("    val ${group.groupToPartName()}: $sessionClassName${group.groupToInterfaceName()} = $sessionClassName${group.groupToInterfaceName()}(api.${group.groupToPartName()}, ${userType.userTypeTokenName()})")
        }
        for(entry in byGroup[null] ?: listOf()) {
            append("    ")
            this.functionHeader(entry, skipAuth = true)
            append(" = api.")
            functionCall(entry, skipAuth = false)
            appendLine()
        }
        for(group in groups) {
            appendLine("    data class $sessionClassName${group.groupToInterfaceName()}(val api: Api.${group.groupToInterfaceName()}, val ${userType.userTypeTokenName()}: String) {")
            for(entry in byGroup[group]!!) {
                append("        ")
                this.functionHeader(entry, skipAuth = true)
                append(" = api.")
                functionCall(entry, skipAuth = false)
                appendLine()
            }
            appendLine("    }")
        }
        appendLine("}")
        appendLine()
    }
}.toString()

fun Documentable.Companion.kotlinLiveApi(packageName: String): String = CodeEmitter(packageName).apply {
    imports.add("io.reactivex.rxjava3.core.Single")
    imports.add("io.reactivex.rxjava3.core.Observable")
    imports.add("com.lightningkite.rx.android.resources.ImageReference")
    imports.add("com.lightningkite.rx.kotlin")
    imports.add("com.lightningkite.rx.okhttp.*")
    imports.add("com.lightningkite.lightningdb.*")
    imports.add("java.util.UUID")
    imports.add("java.util.Optional")
    imports.add("java.time.*")
    val byGroup = safeDocumentables.groupBy { it.docGroup }
    val groups = byGroup.keys.filterNotNull()
    appendLine("class LiveApi(val httpUrl: String, val socketUrl: String = httpUrl): Api {")
    for(group in groups) {
        appendLine("    override val ${group.groupToPartName()}: Live${group.groupToInterfaceName()} = Live${group.groupToInterfaceName()}(httpUrl = httpUrl, socketUrl = socketUrl)")
    }
    for(entry in byGroup[null] ?: listOf()) {
        append("    override ")
        this.functionHeader(entry)
        when(entry) {
            is ApiEndpoint<*, *, *> -> {
                appendLine(" = HttpClient.call(")
                appendLine("        url = \"\$httpUrl${entry.route.path.escaped}\",")
                appendLine("        method = HttpClient.${entry.route.method},")
                entry.authInfo.type?.let {
                    appendLine("        headers = mapOf(\"Authorization\" to \"Bearer ${it.userTypeTokenName()}\"),")
                }
                entry.inputType.takeUnless { it == Unit.serializer() }?.let {
                    appendLine("        body = input.toJsonRequestBody()")
                }
                entry.outputType.takeUnless { it == Unit.serializer() }?.let {
                    appendLine("    ).readJson()")
                } ?: run {
                    appendLine("    ).discard()")
                }
            }
            is ApiWebsocket<*, *, *> -> {
                appendLine(" = multiplexedSocket(url = \"\$httpUrl/multiplex\", path = \"${entry.path}\")")
            }
        }
    }
    for(group in groups) {
        appendLine("    class Live${group.groupToInterfaceName()}(val httpUrl: String, val socketUrl: String = httpUrl): Api.${group.groupToInterfaceName()} {")
        for(entry in byGroup[group]!!) {
            append("        override ")
            this.functionHeader(entry)
            when(entry) {
                is ApiEndpoint<*, *, *> -> {
                    appendLine(" = HttpClient.call(")
                    appendLine("            url = \"\$httpUrl${entry.route.path.escaped}\",")
                    appendLine("            method = HttpClient.${entry.route.method},")
                    entry.authInfo.type?.let {
                        appendLine("            headers = mapOf(\"Authorization\" to \"Bearer \$${it.userTypeTokenName()}\"),")
                    }
                    entry.inputType.takeUnless { it == Unit.serializer() }?.let {
                        appendLine("            body = input.toJsonRequestBody()")
                    }
                    entry.outputType.takeUnless { it == Unit.serializer() }?.let {
                        appendLine("        ).readJson()")
                    } ?: run {
                        appendLine("        ).discard()")
                    }
                }
                is ApiWebsocket<*, *, *> -> {
                    appendLine(" = multiplexedSocket(url = \"\$httpUrl/multiplex\", path = \"${entry.path}\")")
                }
            }
        }
        appendLine("    }")
    }
    appendLine("}")
    appendLine()
}.toString()

private val Documentable.Companion.safeDocumentables get() = (Http.endpoints.values.filterIsInstance<ApiEndpoint<*, *, *>>().filter { it.route.method != HttpMethod.GET || it.inputType == Unit.serializer() } + WebSockets.handlers.values.filterIsInstance<ApiWebsocket<*, *, *>>())
    .distinctBy { it.docGroup.toString() + "/" + it.summary }

private class CodeEmitter(val packageName: String, val body: StringBuilder = StringBuilder()): Appendable by body {
    val imports = mutableSetOf<String>()
    fun append(type: KType) {
        imports.add(type.toString().substringBefore('<').removeSuffix("?"))
        body.append((type.classifier as? KClass<*>)?.simpleName)
        type.arguments.takeIf { it.isNotEmpty() }?.let {
            body.append('<')
            var first = true
            it.forEach {
                if(first) first = false
                else body.append(", ")
                it.type?.let { append(it) } ?: body.append('*')
            }
            body.append('>')
        }
    }
    fun dump(to: Appendable) = with(to) {
        appendLine("package $packageName")
        appendLine()
        imports
            .filter { it.substringAfterLast('.') != packageName }
            .forEach { appendLine("import $it") }
        appendLine()
        append(body)
    }

    override fun toString(): String = StringBuilder().also { dump(it) }.toString()
}

private fun String.groupToInterfaceName(): String = replaceFirstChar { it.uppercase() } + "Api"
private fun String.groupToPartName(): String = replaceFirstChar { it.lowercase() }
private fun String.userTypeTokenName(): String = this.substringAfterLast('.').replaceFirstChar { it.lowercase() }.plus("Token")

private fun KSerializer<*>.kotlinTypeString(emitter: CodeEmitter): String {
    return when {
        this.descriptor.kind == StructureKind.MAP -> "Map<String, ${this.mapValueElement()!!.kotlinTypeString(emitter)}>"
        this.descriptor.kind == StructureKind.LIST -> "List<${this.listElement()!!.kotlinTypeString(emitter)}>"
        this is ContextualSerializer<*> -> this.uncontextualize().kotlinTypeString(emitter)
        else -> {
            descriptor.serialName.substringBefore('<').removeSuffix("?").takeIf { it.contains('.') }?.let { emitter.imports.add(it) }
            descriptor.serialName.substringBefore('<').substringAfterLast('.') + (subSerializers().takeUnless { it.isEmpty() }?.joinToString(", ", "<", ">") { it.kotlinTypeString(emitter) } ?: "")
        }
    }
}

private fun CodeEmitter.functionHeader(documentable: Documentable, skipAuth: Boolean = false) {
    append("fun ${documentable.functionName}(")
    var argComma = false
    arguments(documentable, skipAuth).forEach {
        if(argComma) append(", ")
        else argComma = true
        append(it.name)
        append(": ")
        it.type?.let { append(it.kotlinTypeString(this)) } ?: append(it.stringType ?: "Never")
    }
    append("): ")
    when (documentable) {
        is ApiEndpoint<*, *, *> -> {
            append("Single<")
            append(documentable.outputType.kotlinTypeString(this).let {
                if(it.endsWith("?"))
                    "Optional<${it.removeSuffix("?")}>"
                else it
            })
            append(">")
        }
        is ApiWebsocket<*, *, *> -> {
            append("Observable<WebSocketIsh<")
            append(documentable.inputType.kotlinTypeString(this))
            append(", ")
            append(documentable.outputType.kotlinTypeString(this))
            append(">>")
        }
        else -> TODO()
    }
}

private fun CodeEmitter.functionCall(documentable: Documentable, skipAuth: Boolean = false) {
    append("${documentable.functionName}(")
    var argComma = false
    arguments(documentable, skipAuth).forEach {
        if(argComma) append(", ")
        else argComma = true
        append(it.name)
    }
    append(")")
}

private data class Arg(val name: String, val type: KSerializer<*>? = null, val stringType: String? = null, val default: String? = null)

private fun arguments(documentable: Documentable, skipAuth: Boolean = false): List<Arg> = when (documentable) {
    is ApiEndpoint<*, *, *> -> listOfNotNull(
        documentable.authInfo.type?.takeUnless { skipAuth }?.let {
            Arg(name = it.userTypeTokenName(), stringType = "String")
        }?.let(::listOf),
        documentable.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>()
            .map {
                Arg(name = it.name, type = documentable.routeTypes[it.name], stringType = "String")
            },
        documentable.inputType.takeUnless { it == Unit.serializer() }?.let {
            Arg(name = "input", type = it)
        }?.let(::listOf)
    ).flatten()
    is ApiWebsocket<*, *, *> -> listOfNotNull(
        documentable.authInfo.type?.takeUnless { skipAuth }?.let {
            Arg(name = it.userTypeTokenName(), stringType = "String")
        }?.let(::listOf),
        documentable.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>()
            .map {
                Arg(name = it.name, stringType = "String")
            }
    ).flatten()
    else -> TODO()
}

internal val ServerPath.escaped: String get() = "/" + segments.joinToString("/") {
    when(it) {
        is ServerPath.Segment.Constant -> it.value
        is ServerPath.Segment.Wildcard -> "\${${it.name}}"
    }
} + when(after) {
    ServerPath.Afterwards.None -> ""
    ServerPath.Afterwards.TrailingSlash -> "/"
    ServerPath.Afterwards.ChainedWildcard -> "/*"
}