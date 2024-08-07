@file:OptIn(InternalSerializationApi::class)

package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningdb.MySealedClassSerializer
import com.lightningkite.lightningdb.listElement
import com.lightningkite.lightningdb.mapValueElement
import com.lightningkite.lightningdb.nullElement
import com.lightningkite.lightningserver.camelCase
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.HttpMethod
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.internal.GeneratedSerializer
import kotlin.reflect.KClass
import kotlin.reflect.KType

fun Documentable.Companion.dartSdk(fileName: String, out: Appendable) = with(out) {
    val safeDocumentables =
        endpoints.filter { it.inputType == Unit.serializer() || it.route.method != HttpMethod.GET }.toList()
    appendLine("import 'dart:core';")
    appendLine("import 'package:json_annotation/json_annotation.dart';")
    appendLine("import 'package:uuid/uuid.dart';")
    appendLine("import 'package:http/http.dart' as http;")
    appendLine("import 'dart:convert';")
    appendLine()
    appendLine("part '${fileName.substringBefore(".dart")}.g.dart';")
    appendLine()
    appendLine("typedef Query<T> = Map<String, dynamic>;")
    appendLine("typedef MassModification<T> = Map<String, dynamic>;")
    appendLine("typedef EntryChange<T> = Map<String, dynamic>;")
    appendLine("typedef ListChange<T> = Map<String, dynamic>;")
    appendLine("typedef Modification<T> = Map<String, dynamic>;")
    appendLine("typedef Condition<T> = Map<String, dynamic>;")
    appendLine("typedef GroupCountQuery<T> = Map<String, dynamic>;")
    appendLine("typedef AggregateQuery<T> = Map<String, dynamic>;")
    appendLine("typedef GroupAggregateQuery<T> = Map<String, dynamic>;")
    appendLine("typedef Aggregate<T> = Map<String, dynamic>;")
    appendLine()
    usedTypes
        .sortedBy { it.descriptor.simpleSerialName }
        .filter { it.descriptor.simpleSerialName !in skipSet }
        .forEach {
            when (it.descriptor.kind) {
                is StructureKind.CLASS -> {
                    if (it is MySealedClassSerializer) return@forEach
                    appendLine("@JsonSerializable()")
                    append("class ")
                    it.write().let { out.append(it) }
                    appendLine(" {")
                    (it as? GeneratedSerializer<*>)?.childSerializers()?.forEachIndexed { index, sub ->
                        append("    @JsonKey(name: \"")
                        append(it.descriptor.getElementName(index))
                        append("\") ")
                        out.append(sub.write())
                        append(" ")
                        append(it.descriptor.getElementName(index).filter { it.isLetterOrDigit() })
                        appendLine(";")
                    }
                    append("    ")
                    it.write().let { out.append(it) }
                    append("({")
                    (it as? GeneratedSerializer<*>)?.childSerializers()?.forEachIndexed { index, _ ->
                        append("required this.")
                        append(it.descriptor.getElementName(index).filter { it.isLetterOrDigit() })
                        append(",")
                    }
                    appendLine("});")
                    appendLine("    factory ${it.write()}.fromJson(Map<String, dynamic> json) => _\$${it.write()}FromJson(json);")
                    appendLine("    Map<String, dynamic> toJson() => _\$${it.write()}ToJson(this);")
                    appendLine("}")
                }

                is SerialKind.ENUM -> {
                    append("enum ")
                    it.write().let { out.append(it) }
                    appendLine(" {")
                    for (index in 0 until it.descriptor.elementsCount) {
                        append("    @JsonValue(\"")
                        append(it.descriptor.getElementName(index))
                        append("\") ")
                        append(it.descriptor.getElementName(index).camelCase())
                        appendLine(",")
                    }
                    appendLine("}")
                }

                is PrimitiveKind.STRING -> {
                    if (it.descriptor.simpleSerialName != "String") {
                        appendLine("typedef Api${it.descriptor.simpleSerialName} = String;")
                    }
                }

                else -> {}
            }
        }

    appendLine()
    appendLine()
    appendLine()

    val byGroup = safeDocumentables.groupBy { it.docGroupIdentifier }
    val groups = byGroup.keys.filterNotNull()

    for (group in groups) {
        appendLine("abstract class ${group.groupToInterfaceName()} {")
        for (entry in byGroup[group]!!) {
            append("    ")
            this.functionHeader(entry)
            appendLine(";")
        }
        appendLine("}")
    }

    appendLine("abstract class Api {")
    for (group in groups) {
        appendLine("    ${group.groupToInterfaceName()} get ${group.groupToPartName()};")
    }
    for (entry in byGroup[null] ?: listOf()) {
        append("    ")
        this.functionHeader(entry)
        appendLine(";")
    }
    appendLine("}")

    appendLine()
    appendLine()
    appendLine()

    for (group in groups) {
        appendLine("class Live${group.groupToInterfaceName()} implements ${group.groupToInterfaceName()} {")
        appendLine("    LiveApi parent;")
        appendLine("    Live${group.groupToInterfaceName()}(this.parent);")
        for (entry in byGroup[group]!!) {
            append("    ")
            this.functionHeader(entry, skipAuth = false)
            appendLine(" async {")
            val hasInput = entry.inputType != Unit.serializer()
            appendLine("        var url = Uri.parse(\"\${parent.httpUrl}${entry.route.path.path.escaped}\");")
            appendLine("        var headers = parent.extraHeaders;")
            entry.primaryAuthName?.let {
                if (entry.authOptions.options.contains(null).not()) {
                    appendLine("        headers[\"Authorization\"] = \"Bearer $${it.userTypeTokenName()}\";")
                } else {
                    appendLine("        if (${it.userTypeTokenName()} == null) {")
                    appendLine("            headers[\"Authorization\"] = \"Bearer $${it.userTypeTokenName()}\";")
                    appendLine("        }")
                }
            }
            if (hasInput) {
                appendLine(
                    "        var response = await http.${
                        entry.route.method.toString().lowercase()
                    }(url, headers: headers, body: jsonEncode(${entry.inputType.writeSerialize("input")}));"
                )
            } else {
                appendLine(
                    "        var response = await http.${
                        entry.route.method.toString().lowercase()
                    }(url, headers: headers);"
                )
            }
            appendLine("        if (response.statusCode ~/ 100 != 2) {")
            appendLine("            throw \"${'$'}{response.statusCode} ${'$'}{response.body}\";")
            appendLine("        }")
            entry.outputType.takeUnless { it == Unit.serializer() }?.let {
                appendLine("        return ${it.writeParse("response.body")};")
            }
            appendLine("    }")
        }
        appendLine("}")
    }
    appendLine("class LiveApi implements Api {")
    appendLine("    String httpUrl;")
    appendLine("    String socketUrl;")
    appendLine("    Map<String, String> extraHeaders;")
    for (group in groups) {
        appendLine("    @override late Live${group.groupToInterfaceName()} ${group.groupToPartName()};")
    }
    appendLine("    LiveApi(this.httpUrl, this.socketUrl, [this.extraHeaders = const {}]) {")
    for (group in groups) {
        appendLine("        ${group.groupToPartName()} = Live${group.groupToInterfaceName()}(this);")
    }
    appendLine("    }")
    for (entry in byGroup[null] ?: listOf()) {
        append("    ")
        this.functionHeader(entry, skipAuth = false)
        appendLine(" async {")
        val hasInput = entry.inputType != Unit.serializer()
        appendLine("        var url = Uri.parse(\"\${httpUrl}${entry.path.path.escaped}\");")
        appendLine("        var headers = extraHeaders;")
        entry.primaryAuthName?.let {
            if (entry.authOptions.options.contains(null).not()) {
                appendLine("        headers[\"Authorization\"] = \"Bearer $${it.userTypeTokenName()}\";")
            } else {
                appendLine("        if (${it.userTypeTokenName()} == null) {")
                appendLine("            headers[\"Authorization\"] = \"Bearer $${it.userTypeTokenName()}\";")
                appendLine("        }")
            }
        }
        if (hasInput) {
            appendLine(
                "        var response = await http.${
                    entry.route.method.toString().lowercase()
                }(url, headers: headers, body: jsonEncode(${entry.inputType.writeSerialize("input")}));"
            )
        } else {
            appendLine(
                "        var response = await http.${
                    entry.route.method.toString().lowercase()
                }(url, headers: headers);"
            )
        }
        appendLine("        if (response.statusCode ~/ 100 != 2) {")
        appendLine("            throw \"${'$'}{response.statusCode} ${'$'}{response.body}\";")
        appendLine("        }")
        entry.outputType.takeUnless { it == Unit.serializer() }?.let {
            appendLine("        return ${it.writeParse("response.body")};")
        }
        appendLine("    }")
    }
    appendLine("}")
    appendLine()
}

val Documentable.dartFunctionName: String
    get() = summary.split(' ').joinToString("") { it.replaceFirstChar { it.uppercase() } }
        .replaceFirstChar { it.lowercase() }
        .takeUnless { it == "default" } ?: "defaultItem"

private val fromLightningServerPackage = setOf(
    "Query",
    "MassModification",
    "EntryChange",
    "ListChange",
    "Modification",
    "Condition",
    "GroupCountQuery",
    "AggregateQuery",
    "GroupAggregateQuery",
    "Aggregate",
)
private val skipSet = fromLightningServerPackage + setOf(
    "SortPart",
    "SerializablePropertyPartial",
)

private fun String.groupToInterfaceName(): String = replaceFirstChar { it.uppercase() } + "Api"
private fun String.groupToPartName(): String = replaceFirstChar { it.lowercase() }

@Suppress("UNCHECKED_CAST")
private fun KType?.userTypeTokenName(): String = (this?.classifier as? KClass<Any>)?.userTypeTokenName() ?: "token"
private fun KClass<*>.userTypeTokenName(): String =
    simpleName?.replaceFirstChar { it.lowercase() }?.plus("Token") ?: "token"

private fun Appendable.functionHeader(
    documentable: Documentable,
    skipAuth: Boolean = false,
    overrideUserType: String? = null,
) {
    when (documentable) {
        is ApiEndpoint<*, *, *, *> -> {
            append("Future<")
            documentable.outputType.write().let { append(it) }
            append(">")
        }

        else -> TODO()
    }
    append(" ")
    append(documentable.dartFunctionName)
    append("(")
    var argComma = false
    arguments(documentable, skipAuth, overrideUserType).forEach {
        if (argComma) append(", ")
        else argComma = true
        it.type?.write()?.let { append(it) } ?: it.stringType?.let { append(it) }
//        if(it.optional) append("?")
        append(" ")
        append(it.name)
    }
    append(")")
}

private fun Appendable.functionCall(
    documentable: Documentable,
    skipAuth: Boolean = false,
    authUsesThis: Boolean = false,
    overrideUserType: String? = null,
) {
    append("${documentable.dartFunctionName}(")
    var argComma = false
    arguments(documentable, skipAuth, overrideUserType).forEach {
        if (argComma) append(", ")
        else argComma = true
        if (it.name == documentable.primaryAuthName?.userTypeTokenName() && authUsesThis) {
            append("this.")
        }
        append(it.name)
    }
    append(")")
}

private data class DArg(
    val name: String,
    val type: KSerializer<*>? = null,
    val stringType: String? = null,
    val default: String? = null,
    val optional: Boolean = false,
)

private fun arguments(
    documentable: Documentable,
    skipAuth: Boolean = false,
    overrideUserType: String? = null,
): List<DArg> = when (documentable) {
    is ApiEndpoint<*, *, *, *> -> listOfNotNull(
        documentable.path.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>()
            .mapIndexed { index, it ->
                DArg(name = it.name, type = documentable.path.serializers[index], stringType = "String")
            },
        documentable.inputType.takeUnless { it == Unit.serializer() }?.let {
            DArg(name = "input", type = it)
        }?.let(::listOf),
        documentable.primaryAuthName?.takeUnless { skipAuth }?.let {
            DArg(
                name = (overrideUserType ?: it).userTypeTokenName(),
                stringType = "String",
                optional = !documentable.authOptions.options.contains(null).not()
            )
        }?.let(::listOf)
    ).flatten()

    is ApiWebsocket<*, *, *, *> -> listOfNotNull(
        documentable.primaryAuthName?.takeUnless { skipAuth }?.let {
            DArg(
                name = (overrideUserType ?: it).userTypeTokenName(),
                stringType = "String",
                optional = !documentable.authOptions.options.contains(null).not()
            )
        }?.let(::listOf),
        documentable.path.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>()
            .map {
                DArg(name = it.name, stringType = "String")
            }
    ).flatten()

    else -> TODO()
}


private fun KSerializer<*>.write(): String = nullElement()?.let { it.write() + "?" } ?: if (this == Unit.serializer()) "void" else StringBuilder().also { out ->
    when (descriptor.kind) {
        PrimitiveKind.BOOLEAN -> out.append("bool")
        PrimitiveKind.BYTE,
        PrimitiveKind.SHORT,
        PrimitiveKind.INT,
        PrimitiveKind.LONG,
        -> out.append("int")

        PrimitiveKind.FLOAT,
        PrimitiveKind.DOUBLE,
        -> out.append("double")

        PrimitiveKind.CHAR,
        PrimitiveKind.STRING,
        -> {
            val cleanName = this.descriptor.simpleSerialName
            if (cleanName != "String") {
                out.append("Api$cleanName")
            } else {
                out.append("String")
            }
        }

        StructureKind.LIST -> {
            out.append("List<${this.listElement()!!.write()}>")
        }

        StructureKind.MAP -> {
            out.append("Map")
            listOf("String", this.mapValueElement()!!.write()).joinToString(", ", "<", ">").let {
                out.append(it)
            }
        }

        SerialKind.CONTEXTUAL -> {
            this.uncontextualize().write().let { out.append(it) }
        }

        is PolymorphicKind,
        StructureKind.OBJECT,
        SerialKind.ENUM,
        StructureKind.CLASS,
        -> {
            out.append(descriptor.simpleSerialName)
            this.subSerializers().takeUnless { it.isEmpty() }?.joinToString(", ", "<", ">") { it.write() }?.let {
                out.append(it)
            }
        }
    }
}.toString()

private fun KSerializer<*>.writeSerialize(on: String): String = StringBuilder().also { out ->
    if (this.descriptor.simpleSerialName in skipSet) {
        out.append(on)
        return@also
    }
    when (descriptor.kind) {
        PrimitiveKind.BOOLEAN,
        PrimitiveKind.BYTE,
        PrimitiveKind.SHORT,
        PrimitiveKind.INT,
        PrimitiveKind.LONG,
        PrimitiveKind.FLOAT,
        PrimitiveKind.DOUBLE,
        PrimitiveKind.CHAR,
        PrimitiveKind.STRING -> out.append(on)

        // TODO
        StructureKind.LIST -> {
            out.append(on)
            if (descriptor.isNullable) out.append("?.") else out.append(".")
            out.append("map((e) => ${this.listElement()!!.writeSerialize("e")}).toList()")
        }

        StructureKind.MAP -> {
            out.append(on)
            if (descriptor.isNullable) out.append("?.") else out.append(".")
            out.append("map((k, v) => MapEntry(k, ${this.mapValueElement()!!.writeSerialize("e")}))")
        }

        SerialKind.CONTEXTUAL -> {
            this.uncontextualize().writeSerialize(on).let { out.append(it) }
        }

        is PolymorphicKind,
        StructureKind.OBJECT,
        SerialKind.ENUM,
        StructureKind.CLASS,
        -> {
            out.append("$on.toJson()")
        }
    }
}.toString()

private fun KSerializer<*>.writeParse(on: String): String = StringBuilder().also { out ->
    if (this.descriptor.simpleSerialName in skipSet) {
        out.append("($on as ${write()})")
        return@also
    }
    when (descriptor.kind) {
        PrimitiveKind.BOOLEAN -> out.append("($on as bool)")
        PrimitiveKind.BYTE,
        PrimitiveKind.SHORT,
        PrimitiveKind.INT,
        PrimitiveKind.LONG,
        -> out.append("($on as int)")

        PrimitiveKind.FLOAT,
        PrimitiveKind.DOUBLE,
        -> out.append("($on as double)")

        PrimitiveKind.CHAR,
        PrimitiveKind.STRING,
        -> {
            val cleanName = this.descriptor.simpleSerialName
            if (cleanName != "String") {
                out.append("($on as Api$cleanName)")
            } else {
                out.append("($on as String)")
            }
        }

        StructureKind.LIST -> {
            if (descriptor.isNullable) out.append("($on as List<dynamic>?)?.") else out.append("(jsonDecode($on) as List<dynamic>).")
            out.append("map((e) => ${this.write().replace("List<", "").replace(">", "")}.fromJson(e)).toList()")
        }

        StructureKind.MAP -> {
            if (descriptor.isNullable) out.append("($on as Map<String, dynamic>?)?.") else out.append("($on as Map<String, dynamic>).")
            out.append("map((k, v) => MapEntry(k, ${this.mapValueElement()!!.writeParse("v")}))")
        }

        SerialKind.CONTEXTUAL -> {
            this.uncontextualize().writeParse(on).let { out.append(it) }
        }

        is PolymorphicKind,
        StructureKind.OBJECT,
        SerialKind.ENUM,
        StructureKind.CLASS,
        -> {
            out.append(descriptor.simpleSerialName)
            out.append(".fromJson(jsonDecode($on) as Map<String, dynamic>)")
        }
    }
}.toString()

private val SerialDescriptor.simpleSerialName: String
    get() = serialName.substringBefore('<').substringAfterLast('.').removeSuffix("?")

private fun String.userTypeTokenName(): String =
    this.substringAfterLast('.').replaceFirstChar { it.lowercase() }.plus("Token")


private val ServerPath.escaped: String
    get() = "/" + segments.joinToString("/") {
        when (it) {
            is ServerPath.Segment.Constant -> it.value
            is ServerPath.Segment.Wildcard -> "\${${it.name}}"
        }
    } + when (after) {
        ServerPath.Afterwards.None -> ""
        ServerPath.Afterwards.TrailingSlash -> "/"
        ServerPath.Afterwards.ChainedWildcard -> "/*"
    }