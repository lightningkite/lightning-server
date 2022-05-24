package com.lightningkite.ktorbatteries.typed

import com.lightningkite.ktorbatteries.routes.fullPath
import com.lightningkite.ktorbatteries.routes.maybeMethod
import io.ktor.http.*
import io.ktor.server.routing.*
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.KType

typealias SDK = Sdk
object Sdk {
    fun make(sourceRoot: File, packageName: String) {
        val fileFolder = sourceRoot.resolve(packageName.replace('.', '/'))
        fileFolder.mkdirs()
        fileFolder.resolve("Api.kt").writeText(apiFile(packageName).toString())
        fileFolder.resolve("LiveApi.kt").writeText(liveFile(packageName).toString())
        fileFolder.resolve("Sessions.kt").writeText(sessionFile(packageName).toString())
    }

    private val safeDocumentables = (ApiEndpoint.known.filter { it.route.selector.maybeMethod != HttpMethod.Get || it.inputType == null } + ApiWebsocket.known)
        .distinctBy { it.docGroup.toString() + "/" + it.summary }
    fun apiFile(packageName: String): CodeEmitter = CodeEmitter(packageName).apply {
        imports.add("io.reactivex.rxjava3.core.Single")
        imports.add("io.reactivex.rxjava3.core.Observable")
        imports.add("com.lightningkite.rx.okhttp.*")
        val byGroup = safeDocumentables.groupBy { it.docGroup }
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
    }
    fun sessionFile(packageName: String): CodeEmitter = CodeEmitter(packageName).apply {
        imports.add("io.reactivex.rxjava3.core.Single")
        imports.add("io.reactivex.rxjava3.core.Observable")
        imports.add("com.lightningkite.rx.okhttp.*")
        val byUserType = safeDocumentables.groupBy { it.userType?.classifier as? KClass<*> }
        val userTypes = byUserType.keys.filterNotNull()
        userTypes.forEach { userType ->
            val byGroup = ((byUserType[userType] ?: listOf()) + (byUserType[null]?: listOf())).groupBy { it.docGroup }
            val groups = byGroup.keys.filterNotNull()
            val sessionClassName = "${userType.simpleName}Session"
            appendLine("abstract class Abstract$sessionClassName(val api: Api, val ${userType.userTypeTokenName()}: String) {")
            for(group in groups) {
                appendLine("    val ${group.groupToPartName()}: $sessionClassName${group.groupToInterfaceName()} = $sessionClassName${group.groupToInterfaceName()}(${group.groupToPartName()}, ${userType.userTypeTokenName()})")
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
    }
    fun liveFile(packageName: String): CodeEmitter = CodeEmitter(packageName).apply {
        imports.add("io.reactivex.rxjava3.core.Single")
        imports.add("io.reactivex.rxjava3.core.Observable")
        imports.add("com.lightningkite.rx.android.resources.ImageReference")
        imports.add("com.lightningkite.rx.kotlin")
        imports.add("com.lightningkite.rx.okhttp.*")
        imports.add("com.lightningkite.ktordb.live.*")
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
                    appendLine("        url = \"\$httpUrl${entry.route.fullPath}\",")
                    appendLine("        method = HttpClient.${entry.route.selector.maybeMethod?.value?.uppercase() ?: "GET"},")
                    entry.inputType?.let {
                        appendLine("        body = input.toJsonRequestBody()")
                    }
                    entry.outputType?.let {
                        appendLine("    ).readJson()")
                    } ?: run {
                        appendLine("    ).discard()")
                    }
                }
                is ApiWebsocket<*, *, *> -> {
                    appendLine(" = multiplexedSocket(url = \"\$httpUrl/multiplex\", path = \"${entry.route.fullPath}\")")
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
                        appendLine("            url = \"\$httpUrl${entry.route.fullPath}\",")
                        appendLine("            method = HttpClient.${entry.route.selector.maybeMethod?.value?.uppercase() ?: "GET"},")
                        entry.inputType?.let {
                            appendLine("            body = input.toJsonRequestBody()")
                        }
                        entry.outputType?.let {
                            appendLine("        ).readJson()")
                        } ?: run {
                            appendLine("        ).discard()")
                        }
                    }
                    is ApiWebsocket<*, *, *> -> {
                        appendLine(" = multiplexedSocket(url = \"\$httpUrl/multiplex\", path = \"${entry.route.fullPath}\")")
                    }
                }
            }
            appendLine("    }")
        }
        appendLine("}")
        appendLine()
    }
}

public class CodeEmitter(val packageName: String, val body: StringBuilder = StringBuilder()): Appendable by body {
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
private fun KType?.userTypeTokenName(): String = (this?.classifier as? KClass<Any>)?.userTypeTokenName() ?: "token"
private fun KClass<*>.userTypeTokenName(): String = simpleName?.replaceFirstChar { it.lowercase() } ?.plus("Token") ?: "token"

private fun CodeEmitter.functionHeader(documentable: Documentable, skipAuth: Boolean = false) {
    append("fun ${documentable.functionName}(")
    var argComma = false
    arguments(documentable, skipAuth).forEach {
        if(argComma) append(", ")
        else argComma = true
        append(it.name)
        append(": ")
        it.type?.let { append(it) } ?: append(it.stringType ?: "Never")
    }
    append("): ")
    when (documentable) {
        is ApiEndpoint<*, *, *> -> {
            documentable.outputType?.let {
                append("Single<")
                append(it)
                append(">")
            } ?: append("Single<Unit>")
        }
        is ApiWebsocket<*, *, *> -> {
            append("Observable<WebSocketIsh<")
            append(documentable.inputType)
            append(", ")
            append(documentable.outputType)
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

private data class Arg(val name: String, val type: KType? = null, val stringType: String? = null, val default: String? = null)

private fun arguments(documentable: Documentable, skipAuth: Boolean = false): List<Arg> = when (documentable) {
    is ApiEndpoint<*, *, *> -> listOfNotNull(
        documentable.userType?.takeUnless { skipAuth }?.let {
            Arg(name = it.userTypeTokenName(), stringType = "String")
        }?.let(::listOf),
        generateSequence(documentable.route) { it.parent }.toList().reversed()
            .mapNotNull { it.selector as? PathSegmentParameterRouteSelector }
            .map {
                Arg(name = it.name, type = documentable.routeTypes[it.name], stringType = "String")
            },
        documentable.inputType?.let {
            Arg(name = "input", type = it)
        }?.let(::listOf)
    ).flatten()
    is ApiWebsocket<*, *, *> -> listOfNotNull(
        documentable.userType?.takeUnless { skipAuth }?.let {
            Arg(name = it.userTypeTokenName(), stringType = "String")
        }?.let(::listOf),
        generateSequence(documentable.route) { it.parent }.toList().reversed()
            .mapNotNull { it.selector as? PathSegmentParameterRouteSelector }
            .map {
                Arg(name = it.name, stringType = "String")
            }
    ).flatten()
    else -> TODO()
}
