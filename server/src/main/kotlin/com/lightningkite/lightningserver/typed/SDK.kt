package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.Http
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.websocket.WebSockets
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.KType

fun Documentable.Companion.kotlinApi(packageName: String): String = CodeEmitter(packageName).apply {
    imports.add("io.reactivex.rxjava3.core.Single")
    imports.add("io.reactivex.rxjava3.core.Observable")
    imports.add("com.lightningkite.rx.okhttp.*")
    imports.add("com.lightningkite.lightningdb.live.*")
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
    imports.add("com.lightningkite.lightningdb.live.*")
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
    imports.add("com.lightningkite.lightningdb.live.*")
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

private val Documentable.Companion.safeDocumentables get() = (Http.routes.values.filterIsInstance<ApiEndpoint<*, *, *>>().filter { it.route.method != HttpMethod.GET || it.inputType == Unit.serializer() } + WebSockets.handlers.values.filterIsInstance<ApiWebsocket<*, *, *>>())
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

private fun CodeEmitter.functionHeader(documentable: Documentable, skipAuth: Boolean = false) {
    append("fun ${documentable.functionName}(")
    var argComma = false
    arguments(documentable, skipAuth).forEach {
        if(argComma) append(", ")
        else argComma = true
        append(it.name)
        append(": ")
        it.type?.let { append(it.descriptor.serialName) } ?: append(it.stringType ?: "Never")
    }
    append("): ")
    when (documentable) {
        is ApiEndpoint<*, *, *> -> {
            append("Single<")
            append(documentable.outputType.descriptor.serialName)
            append(">")
        }
        is ApiWebsocket<*, *, *> -> {
            append("Observable<WebSocketIsh<")
            append(documentable.inputType.descriptor.serialName)
            append(", ")
            append(documentable.outputType.descriptor.serialName)
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