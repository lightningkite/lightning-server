package com.lightningkite.ktorbatteries.typed

import com.lightningkite.ktorbatteries.routes.fullPath
import com.lightningkite.ktorbatteries.routes.maybeMethod
import com.lightningkite.ktorbatteries.serialization.Serialization
import io.ktor.http.*
import io.ktor.server.routing.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.serializer
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.KType

object TypescriptSdk {
    fun make(sourceRoot: File, packageName: String) {
        val fileFolder = sourceRoot.resolve(packageName.replace('.', '/'))
        fileFolder.mkdirs()
        fileFolder.resolve("Types.ts").printWriter().use { typesFile(it) }
        fileFolder.resolve("Api.ts").writeText(apiFile(packageName).toString())
        fileFolder.resolve("LiveApi.ts").writeText(liveFile(packageName).toString())
        fileFolder.resolve("Sessions.ts").writeText(sessionFile(packageName).toString())
    }

    fun typesFile(out: Appendable) = with(out) {
        fun SerialDescriptor.allTypes() = sequenceOf(this) + elementDescriptors
        safeDocumentables.asSequence().flatMap {
            when (it) {
                is ApiEndpoint<*, *, *> -> sequenceOf(it.inputType, it.outputType)
                is ApiWebsocket<*, *, *> -> sequenceOf(it.inputType, it.outputType)
                else -> sequenceOf()
            }
        }
            .filterNotNull()
            .distinct()
            .map { Serialization.json.serializersModule.serializer(it).descriptor }
            .flatMap { it.allTypes() }
            .map {
                if(it.kind == SerialKind.CONTEXTUAL)
                    Serialization.json.serializersModule.getContextualDescriptor(it)!!
                else
                    it
            }
            .distinct()
            .filter { !it.serialName.contains("Condition") && !it.serialName.contains("Modification") }
            .filter { !it.isNullable }
            .forEach {
                when(it.kind) {
                    is StructureKind.CLASS -> {
                        append("interface ")
                        it.write(out)
                        appendLine(" {")
                        for(index in 0 until it.elementsCount) {
                            append("    ")
                            append(it.getElementName(index))
                            append(": ")
                            it.getElementDescriptor(index).write(out)
                            appendLine()
                        }
                        appendLine("}")
                    }
                    is SerialKind.ENUM -> {
                        append("enum ")
                        it.write(out)
                        appendLine(" {")
                        for(index in 0 until it.elementsCount) {
                            append("    ")
                            append(it.getElementName(index))
                            append(" = \"")
                            it.getElementDescriptor(index).write(out)
                            append("\",")
                            appendLine()
                        }
                        appendLine("}")
                    }
                }
            }
    }

    private val safeDocumentables =
        (ApiEndpoint.known.filter { it.route.selector.maybeMethod != HttpMethod.Get || it.inputType == null } + ApiWebsocket.known)
            .distinctBy { it.docGroup.toString() + "/" + it.summary }

    fun apiFile(packageName: String): CodeEmitter = CodeEmitter(packageName).apply {
        imports.add("io.reactivex.rxjava3.core.Single")
        imports.add("io.reactivex.rxjava3.core.Observable")
        imports.add("com.lightningkite.rx.okhttp.*")
        imports.add("com.lightningkite.ktordb.live.*")
        val byGroup = safeDocumentables.groupBy { it.docGroup }
        val groups = byGroup.keys.filterNotNull()
        appendLine("interface Api {")
        for (group in groups) {
            appendLine("    readonly ${group.groupToPartName()}: ${group.groupToInterfaceName()}")
        }
        for (entry in byGroup[null] ?: listOf()) {
            append("    ")
            this.functionHeader(entry)
            appendLine()
        }
        for (group in groups) {
            appendLine("    interface ${group.groupToInterfaceName()} {")
            for (entry in byGroup[group]!!) {
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
        imports.add("com.lightningkite.ktordb.live.*")
        val byUserType = safeDocumentables.groupBy { it.userType?.classifier as? KClass<*> }
        val userTypes = byUserType.keys.filterNotNull()
        userTypes.forEach { userType ->
            val byGroup = ((byUserType[userType] ?: listOf()) + (byUserType[null] ?: listOf())).groupBy { it.docGroup }
            val groups = byGroup.keys.filterNotNull()
            val sessionClassName = "${userType.simpleName}Session"
            appendLine("abstract class Abstract$sessionClassName(api: Api, ${userType.userTypeTokenName()}: String) {")
            appendLine("    abstract val api: Api")
            appendLine("    abstract val ${userType.userTypeTokenName()}: String")
            for (group in groups) {
                appendLine("    val ${group.groupToPartName()}: $sessionClassName${group.groupToInterfaceName()} = $sessionClassName${group.groupToInterfaceName()}(api.${group.groupToPartName()}, ${userType.userTypeTokenName()})")
            }
            for (entry in byGroup[null] ?: listOf()) {
                append("    ")
                this.functionHeader(entry, skipAuth = true)
                append(" = api.")
                functionCall(entry, skipAuth = false)
                appendLine()
            }
            for (group in groups) {
                appendLine("    data class $sessionClassName${group.groupToInterfaceName()}(val api: Api.${group.groupToInterfaceName()}, val ${userType.userTypeTokenName()}: String) {")
                for (entry in byGroup[group]!!) {
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
        for (group in groups) {
            appendLine("    override val ${group.groupToPartName()}: Live${group.groupToInterfaceName()} = Live${group.groupToInterfaceName()}(httpUrl = httpUrl, socketUrl = socketUrl)")
        }
        for (entry in byGroup[null] ?: listOf()) {
            append("    override ")
            this.functionHeader(entry)
            when (entry) {
                is ApiEndpoint<*, *, *> -> {
                    appendLine(" = HttpClient.call(")
                    appendLine("        url = \"\$httpUrl${entry.route.fullPath}\",")
                    appendLine("        method = HttpClient.${entry.route.selector.maybeMethod?.value?.uppercase() ?: "GET"},")
                    entry.userType?.let {
                        appendLine("        headers = mapOf(\"Authorization\" to \"Bearer ${it.userTypeTokenName()}\"),")
                    }
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
        for (group in groups) {
            appendLine("    class Live${group.groupToInterfaceName()}(val httpUrl: String, val socketUrl: String = httpUrl): Api.${group.groupToInterfaceName()} {")
            for (entry in byGroup[group]!!) {
                append("        override ")
                this.functionHeader(entry)
                when (entry) {
                    is ApiEndpoint<*, *, *> -> {
                        appendLine(" = HttpClient.call(")
                        appendLine("            url = \"\$httpUrl${entry.route.fullPath}\",")
                        appendLine("            method = HttpClient.${entry.route.selector.maybeMethod?.value?.uppercase() ?: "GET"},")
                        entry.userType?.let {
                            appendLine("            headers = mapOf(\"Authorization\" to \"Bearer \$${it.userTypeTokenName()}\"),")
                        }
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

private fun String.groupToInterfaceName(): String = replaceFirstChar { it.uppercase() } + "Api"
private fun String.groupToPartName(): String = replaceFirstChar { it.lowercase() }
private fun KType?.userTypeTokenName(): String = (this?.classifier as? KClass<Any>)?.userTypeTokenName() ?: "token"
private fun KClass<*>.userTypeTokenName(): String =
    simpleName?.replaceFirstChar { it.lowercase() }?.plus("Token") ?: "token"

private fun Appendable.functionHeader(documentable: Documentable, skipAuth: Boolean = false) {
    append("fun ${documentable.functionName}(")
    var argComma = false
    arguments(documentable, skipAuth).forEach {
        if (argComma) append(", ")
        else argComma = true
        append(it.name)
        append(": ")
        it.type?.let { Serialization.json.serializersModule.serializer(it) }?.descriptor?.write(this)
    }
    append("): ")
    when (documentable) {
        is ApiEndpoint<*, *, *> -> {
            documentable.outputType?.let {
                append("Single<")
                it.let { Serialization.json.serializersModule.serializer(it) }.descriptor.write(this)
                append(">")
            } ?: append("Single<Unit>")
        }
        is ApiWebsocket<*, *, *> -> {
            append("Observable<WebSocketIsh<")
            documentable.inputType.let { Serialization.json.serializersModule.serializer(it) }.descriptor.write(this)
            append(", ")
            documentable.outputType.let { Serialization.json.serializersModule.serializer(it) }.descriptor.write(this)
            append(">>")
        }
        else -> TODO()
    }
}

private fun Appendable.functionCall(documentable: Documentable, skipAuth: Boolean = false) {
    append("${documentable.functionName}(")
    var argComma = false
    arguments(documentable, skipAuth).forEach {
        if (argComma) append(", ")
        else argComma = true
        append(it.name)
    }
    append(")")
}

private data class TArg(
    val name: String,
    val type: KType? = null,
    val stringType: String? = null,
    val default: String? = null
)

private fun arguments(documentable: Documentable, skipAuth: Boolean = false): List<TArg> = when (documentable) {
    is ApiEndpoint<*, *, *> -> listOfNotNull(
        documentable.userType?.takeUnless { skipAuth }?.let {
            TArg(name = it.userTypeTokenName(), stringType = "String")
        }?.let(::listOf),
        generateSequence(documentable.route) { it.parent }.toList().reversed()
            .mapNotNull { it.selector as? PathSegmentParameterRouteSelector }
            .map {
                TArg(name = it.name, type = documentable.routeTypes[it.name], stringType = "String")
            },
        documentable.inputType?.let {
            TArg(name = "input", type = it)
        }?.let(::listOf)
    ).flatten()
    is ApiWebsocket<*, *, *> -> listOfNotNull(
        documentable.userType?.takeUnless { skipAuth }?.let {
            TArg(name = it.userTypeTokenName(), stringType = "String")
        }?.let(::listOf),
        generateSequence(documentable.route) { it.parent }.toList().reversed()
            .mapNotNull { it.selector as? PathSegmentParameterRouteSelector }
            .map {
                TArg(name = it.name, stringType = "String")
            }
    ).flatten()
    else -> TODO()
}


private fun SerialDescriptor.write(out: Appendable): Unit {
    when (kind) {
        PrimitiveKind.BOOLEAN -> out.append("boolean")
        PrimitiveKind.BYTE,
        PrimitiveKind.SHORT,
        PrimitiveKind.INT,
        PrimitiveKind.LONG,
        PrimitiveKind.FLOAT,
        PrimitiveKind.DOUBLE -> out.append("number")
        PrimitiveKind.CHAR,
        PrimitiveKind.STRING -> out.append("string")
        StructureKind.LIST -> {
            out.append("Array<")
            this.getElementDescriptor(0).write(out)
            out.append(">")
        }
        StructureKind.MAP -> {
            out.append("Record<")
            this.getElementDescriptor(0).write(out)
            out.append(", ")
            this.getElementDescriptor(1).write(out)
            out.append(">")
        }
        SerialKind.CONTEXTUAL -> {
            Serialization.json.serializersModule.getContextualDescriptor(this)?.write(out) ?: out.append(this.serialName.substringAfterLast('.'))
        }
        is PolymorphicKind,
        StructureKind.OBJECT,
        SerialKind.ENUM,
        StructureKind.CLASS -> out.append(serialName.substringAfterLast('.'))
    }
}
