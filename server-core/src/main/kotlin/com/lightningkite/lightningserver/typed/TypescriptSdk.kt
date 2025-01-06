package com.lightningkite.lightningserver.typed

import com.lightningkite.UUID
import com.lightningkite.lightningdb.*
import com.lightningkite.serialization.*
import com.lightningkite.serialization.*
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.HttpMethod
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlin.reflect.KClass
import kotlin.reflect.KType

fun Documentable.Companion.typescriptSdk(out: Appendable) = with(out) {
    val safeDocumentables =
        endpoints.filter { it.inputType == Unit.serializer() || it.route.method != HttpMethod.GET }.toList()
    appendLine("import { ${fromLightningServerPackage.joinToString()}, apiCall, Path, DeepPartial } from '@lightningkite/lightning-server-simplified'")
    appendLine()
    usedTypes
        .filter { it.descriptor.simpleSerialName !in skipFromLsPackage }
        .sortedBy { it.descriptor.simpleSerialName }
        .forEach {
            when (it.descriptor.kind) {
                is StructureKind.CLASS -> {
                    if (it is MySealedClassSerializer) return@forEach
//                    emitTypeComment(it)
                    append("export interface ")
                    it.write().let { out.append(it) }
                    appendLine(" {")
                    (it.serializableProperties?.map { it.serializer } ?: it.tryChildSerializers()?.toList()
                    ?: listOf()).forEachIndexed { index, sub ->
                        append("    ")
                        append(it.descriptor.getElementName(index))
                        append(": ")
                        out.append(sub.write())
                        appendLine()
                    }
                    appendLine("}")
                }

                is SerialKind.ENUM -> {
//                    emitTypeComment(it)
                    append("export enum ")
                    it.write().let { out.append(it) }
                    appendLine(" {")
                    for (index in 0 until it.descriptor.elementsCount) {
                        append("    ")
                        it.descriptor.getElementName(index).first()
                            .let { if (it.isJavaIdentifierStart()) it else '_' }.let { append(it) }
                        it.descriptor.getElementName(index).drop(1)
                            .map { if (it.isJavaIdentifierPart()) it else '_' }.forEach { append(it) }
                        append(" = \"")
                        append(it.descriptor.getElementName(index))
                        append("\",")
                        appendLine()
                    }
                    appendLine("}")
                }

                is PrimitiveKind.STRING -> {
                    if (it.descriptor.simpleSerialName != "String") {
//                        emitTypeComment(it)
                        appendLine("type ${it.descriptor.simpleSerialName} = string  // ${it.descriptor.serialName}")
                    }
                }

                else -> {}
            }
        }

    appendLine()
    appendLine()
    appendLine()

    val byGroup = safeDocumentables.groupBy { it.docGroupIdentifier }
    val groups = byGroup.keys.filterNotNull().sortedBy { it.groupToPartName() }
    appendLine("export interface Api {")
    for (entry in byGroup[null]?.sortedBy { it.functionName } ?: listOf()) {
        appendLine("    ")
        appendLine("     /**")
        entry.description.split('\n').map { it.trim() }.forEach {
            appendLine("     * $it")
        }
        appendLine("     **/")
        append("    ")
        append(entry.functionName)
        this.functionHeader(entry)
        appendLine()
    }
    for (group in groups) {
        appendLine("    readonly ${group.groupToPartName()}: {")
        for (entry in byGroup[group]!!) {
            appendLine("        ")
            appendLine("        /**")
            entry.description.split('\n').map { it.trim() }.forEach {
                appendLine("        * $it")
            }
            appendLine("        **/")
            append("        ")
            append(entry.functionName)
            this.functionHeader(entry)
            appendLine()
        }
        appendLine("    }")
    }
    appendLine("}")

    appendLine()
    appendLine()
    appendLine()

    val byUserType = safeDocumentables.groupBy { it.primaryAuthName }
    val userTypes = byUserType.keys.filterNotNull()
    userTypes.forEach { userType ->
        @Suppress("NAME_SHADOWING") val byGroup =
            ((byUserType[userType] ?: listOf()) + (byUserType[null] ?: listOf())).groupBy { it.docGroupIdentifier }
        @Suppress("NAME_SHADOWING") val groups = byGroup.keys.filterNotNull().sortedBy { it.groupToPartName() }
        val sessionClassName = "${userType.substringAfterLast('.')}Session"
        appendLine("export class $sessionClassName {")
        appendLine("    constructor(public api: Api, public ${userType.userTypeTokenName()}: ()=>string) {}")
        for (entry in byGroup[null]?.sortedBy { it.functionName } ?: listOf()) {
            appendLine("    ")
            appendLine("    /**")
            entry.description.split('\n').map { it.trim() }.forEach {
                appendLine("    * $it")
            }
            appendLine("    **/")
            append("    ")
            append(entry.functionName)
            this.functionHeader(entry, skipAuth = true)
            append(" { return this.api.")
            functionCall(entry, skipAuth = false, authUsesThis = true, overrideUserType = userType)
            appendLine(" } ")
        }
        for (group in groups) {
            appendLine("    readonly ${group.groupToPartName()} = {")
            for (entry in byGroup[group]!!) {
                appendLine("        ")
                appendLine("        /**")
                entry.description.split('\n').map { it.trim() }.forEach {
                    appendLine("        * $it")
                }
                appendLine("        **/")
                append("        ")
                append(entry.functionName)
                append(": ")
                this.functionHeader(entry, skipAuth = true)
                append(" => { return this.api.")
                append(group.groupToPartName())
                append(".")
                functionCall(entry, skipAuth = false, authUsesThis = true, overrideUserType = userType)
                appendLine(" }, ")
            }
            appendLine("    }")
        }
        appendLine("}")
        appendLine()
    }

    appendLine()
    appendLine()
    appendLine()

    appendLine("export class LiveApi implements Api {")
    appendLine("    public constructor(public httpUrl: string, public socketUrl: string = httpUrl, public extraHeaders: Record<string, string> = {}, public responseInterceptors?: (x: Response)=>Response) {}")
    for (entry in byGroup[null]?.sortedBy { it.functionName } ?: listOf()) {
        appendLine("    ")
        appendLine("    /**")
        entry.description.split('\n').map { it.trim() }.forEach {
            appendLine("    * $it")
        }
        appendLine("    **/")
        append("    ")
        append(entry.functionName)
        this.functionHeader(entry, skipAuth = false)
        appendLine(" {")
        val hasInput = entry.inputType != Unit.serializer()
        appendLine("        return apiCall<${entry.inputType.write()}>(")
        appendLine("            `\${this.httpUrl}${entry.path.path.escaped}`,")
        appendLine("            ${if (hasInput) "input" else "undefined"},")
        appendLine("            {")
        appendLine("                method: \"${entry.route.method}\",")
        entry.primaryAuthName?.let {
            appendLine("                headers: ${it.userTypeTokenName()} ? { ...this.extraHeaders, \"Authorization\": `Bearer \${${it.userTypeTokenName()}}` } : this.extraHeaders,")
        }
        appendLine("            }, ")
        appendLine("            undefined,")
        appendLine("            this.responseInterceptors, ")
        entry.outputType.takeUnless { it == Unit.serializer() }?.let {
            appendLine("        ).then(x => x.json())")
        } ?: run {
            appendLine("        ).then(x => undefined)")
        }
        appendLine("    }")
    }
    for (group in groups.sortedBy { it.groupToPartName() }) {
        appendLine("    readonly ${group.groupToPartName()} = {")
        for (entry in byGroup[group]!!) {
            appendLine("        ")
            appendLine("        /**")
            entry.description.split('\n').map { it.trim() }.forEach {
                appendLine("        * $it")
            }
            appendLine("        **/")
            append("        ")
            append(entry.functionName)
            append(": ")
            this.functionHeader(entry, skipAuth = false)
            appendLine(" => {")
            val hasInput = entry.inputType != Unit.serializer()
            appendLine("            return apiCall<${entry.inputType.write()}>(")
            appendLine("                `\${this.httpUrl}${entry.path.path.escaped}`,")
            appendLine("                ${if (hasInput) "input" else "undefined"},")
            appendLine("                {")
            appendLine("                    method: \"${entry.route.method}\",")
            entry.primaryAuthName?.let {
                appendLine("                    headers: ${it.userTypeTokenName()} ? { ...this.extraHeaders, \"Authorization\": `Bearer \${${it.userTypeTokenName()}}` } : this.extraHeaders,")
            }
            appendLine("                }, ")
            appendLine("                undefined,")
            appendLine("                this.responseInterceptors, ")
            entry.outputType.takeUnless { it == Unit.serializer() }?.let {
                appendLine("            ).then(x => x.json())")
            } ?: run {
                appendLine("            ).then(x => undefined)")
            }
            appendLine("        },")
        }
        appendLine("    }")
    }
    appendLine("}")
    appendLine()
}


fun Documentable.Companion.simpleTypescriptSdk(out: Appendable) = with(out) {
    val safeDocumentables =
        endpoints.filter { it.inputType == Unit.serializer() || it.route.method != HttpMethod.GET }.toList()
    appendLine("import { ${fromLightningServerPackage.joinToString()}, apiCall, Path, DeepPartial } from '@lightningkite/lightning-server-simplified'")
    appendLine()


    append(
        """
        interface AutoEndpoints<T> {
            default(userToken: string): Promise<T>
            query(input: Query<T>, userToken: string): Promise<Array<T>>
            queryPartial(input: QueryPartial<T>, userToken: string): Promise<Array<DeepPartial<T>>>
            detail(id: UUID, userToken: string): Promise<T>
            insertBulk(input: Array<T>, userToken: string): Promise<Array<T>>
            insert(input: T, userToken: string): Promise<T>
            upsert(id: UUID, input: T, userToken: string): Promise<T>
            bulkReplace(input: Array<T>, userToken: string): Promise<Array<T>>
            replace(id: UUID, input: T, userToken: string): Promise<T>
            bulkModify(input: MassModification<T>, userToken: string): Promise<number>
            modifyWithDiff(id: UUID, input: Modification<T>, userToken: string): Promise<EntryChange<T>>
            modify(id: UUID, input: Modification<T>, userToken: string): Promise<T>
            simplifiedModify(id: UUID, input: DeepPartial<T>, userToken: string): Promise<T>
            bulkDelete(input: Condition<T>, userToken: string): Promise<number>
            delete(id: UUID, userToken: string): Promise<void>
            count(input: Condition<T>, userToken: string): Promise<number>
            groupCount(input: GroupCountQuery<T>, userToken: string): Promise<Record<string, number>>
            aggregate(input: AggregateQuery<T>, userToken: string): Promise<number | null | undefined>
            groupAggregate(input: GroupAggregateQuery<T>, userToken: string): Promise<Record<string, number | null | undefined>>
        }
    """.trimIndent()
    )
    appendLine()
    appendLine()
    usedTypes
        .filter { it.descriptor.simpleSerialName !in skipFromLsPackage }
        .sortedBy { it.descriptor.simpleSerialName }
        .distinctBy { it.write() }
        .forEach {
            when (it.descriptor.kind) {
                is StructureKind.CLASS -> {
                    if (it is MySealedClassSerializer) return@forEach

                    val genericMap: Map<String, String> = it.getGenerics()
                        ?.withIndex()
                        ?.associate { (index, value) ->
                            value.write() to "T${if (index > 0) index else ""}"
                        } ?: emptyMap()


                    fun String.replaceGenerics(): String =
                        genericMap.entries.fold(this) { acc, (old, new) -> acc.replace(old, new) }

                    appendLine("export interface ${it.write().replaceGenerics()} {")
                    (it.serializableProperties?.map { it.serializer } ?: it.tryChildSerializers()?.toList()
                    ?: listOf()).forEachIndexed { index, sub ->
                        appendLine("    ${it.descriptor.getElementName(index)}: ${sub.write().replaceGenerics()}")
                    }
                    appendLine("}")
                }

                is SerialKind.ENUM -> {
                    append("export enum ")
                    it.write().let { out.append(it) }
                    appendLine(" {")
                    for (index in 0 until it.descriptor.elementsCount) {
                        append("    ")
                        it.descriptor.getElementName(index).first()
                            .let { if (it.isJavaIdentifierStart()) it else '_' }.let { append(it) }
                        it.descriptor.getElementName(index).drop(1)
                            .map { if (it.isJavaIdentifierPart()) it else '_' }.forEach { append(it) }
                        append(" = \"")
                        append(it.descriptor.getElementName(index))
                        append("\",")
                        appendLine()
                    }
                    appendLine("}")
                }

                is PrimitiveKind.STRING -> {
                    if (it.descriptor.simpleSerialName != "String") {
                        appendLine(
                            "type ${it.descriptor.simpleSerialName} = string  // ${it.descriptor.serialName}".replace(
                                "/loose",
                                ""
                            )
                        )
                    }
                }

                else -> {}
            }
        }

    appendLine()
    appendLine()
    appendLine()


    val byGroup = safeDocumentables.groupBy { it.docGroupIdentifier }
    val groups = byGroup.keys.filterNotNull().sortedBy { it.groupToPartName() }
    appendLine("export interface Api {")
    for (entry in byGroup[null]?.sortedBy { it.functionName } ?: listOf()) {
        append("    ")
        append(entry.functionName)
        this.functionHeader(entry)
        appendLine()
    }
    appendLine()
    for (group in groups) {

        if (byGroup[group].hasAutoEndpoints()) {
            val autoGeneratedInterface =
                byGroup[group]!!.firstOrNull { it.functionName == "detail" }?.outputType?.write()
            append("    readonly ${group.groupToPartName()}: AutoEndpoints<${autoGeneratedInterface}>")

            val nonAutogeneratedEndpoints = byGroup[group]!!.filterNot { autoGeneratedNames.contains(it.functionName) }
            if (nonAutogeneratedEndpoints.isNotEmpty()) {
                appendLine(" & {")
                for (entry in nonAutogeneratedEndpoints) {
                    append("        ")
                    append(entry.functionName)
                    this.functionHeader(entry)
                    appendLine()
                }
                appendLine("    }")
            } else {
                appendLine()
            }
        } else {
            appendLine("    readonly ${group.groupToPartName()}: {")
            for (entry in byGroup[group]!!) {
                append("        ")
                append(entry.functionName)
                this.functionHeader(entry)
                appendLine()
            }
            appendLine("    }")
        }
    }
    appendLine("}")

    appendLine()
    appendLine()
    appendLine()

    val byUserType = safeDocumentables.groupBy { it.primaryAuthName }
    val userTypes = byUserType.keys.filterNotNull()
    userTypes.forEach { userType ->
        @Suppress("NAME_SHADOWING") val byGroup =
            ((byUserType[userType] ?: listOf()) + (byUserType[null] ?: listOf())).groupBy { it.docGroupIdentifier }
        @Suppress("NAME_SHADOWING") val groups = byGroup.keys.filterNotNull().sortedBy { it.groupToPartName() }

        val sessionClassName = "${userType.substringAfterLast('.')}Session"
        appendLine("export class $sessionClassName {")
        appendLine("    constructor(public api: Api, public ${userType.userTypeTokenName()}: () => string) {}")
        for (entry in byGroup[null]?.sortedBy { it.functionName } ?: listOf()) {
            appendLine("    ")
            append("    ")
            append(entry.functionName)
            this.functionHeader(entry, skipAuth = true)
            append(" { return this.api.")
            functionCall(entry, skipAuth = false, authUsesThis = true, overrideUserType = userType)
            appendLine(" } ")
        }

        appendLine("""
        generateAutoEndpoints = <M, K extends AutoEndpoints<M>>(endpoint: K): AutoEndpoints<M> => ({
            default: () => endpoint.default(this.${userType.userTypeTokenName()}()),
            query: (input) => endpoint.query(input, this.${userType.userTypeTokenName()}()),
            queryPartial: (input) => endpoint.queryPartial(input, this.${userType.userTypeTokenName()}()),
            detail: (id) => endpoint.detail(id, this.${userType.userTypeTokenName()}()),
            insertBulk: (input) => endpoint.insertBulk(input, this.${userType.userTypeTokenName()}()),
            insert: (input) => endpoint.insert(input, this.${userType.userTypeTokenName()}()),
            upsert: (id, input) => endpoint.upsert(id, input, this.${userType.userTypeTokenName()}()),
            bulkReplace: (input) => endpoint.bulkReplace(input, this.${userType.userTypeTokenName()}()),
            replace: (id, input) => endpoint.replace(id, input, this.${userType.userTypeTokenName()}()),
            bulkModify: (input) => endpoint.bulkModify(input, this.${userType.userTypeTokenName()}()),
            modifyWithDiff: (id, input) => endpoint.modifyWithDiff(id, input, this.${userType.userTypeTokenName()}()),
            modify: (id, input) => endpoint.modify(id, input, this.${userType.userTypeTokenName()}()),
            simplifiedModify: (id, input) => endpoint.simplifiedModify(id, input, this.${userType.userTypeTokenName()}()),
            bulkDelete: (input) => endpoint.bulkDelete(input, this.${userType.userTypeTokenName()}()),
            delete: (id) => endpoint.delete(id, this.${userType.userTypeTokenName()}()),
            count: (input) => endpoint.count(input, this.${userType.userTypeTokenName()}()),
            groupCount: (input) => endpoint.groupCount(input, this.${userType.userTypeTokenName()}()),
            aggregate: (input) => endpoint.aggregate(input, this.${userType.userTypeTokenName()}()),
            groupAggregate: (input) => endpoint.groupAggregate(input, this.${userType.userTypeTokenName()}()),
    })
        """.trimIndent())
        appendLine()

        for (group in groups) {

            if (byGroup[group].hasAutoEndpoints()) {
                val nonAutogeneratedEndpoints = byGroup[group]!!.filterNot { autoGeneratedNames.contains(it.functionName) }
                appendLine("    readonly ${group.groupToPartName()}: Api[\"${group.groupToPartName()}\"] = {")
                append("        ")
                appendLine("...this.generateAutoEndpoints(this.api.${group.groupToPartName()}),")
                for (entry in nonAutogeneratedEndpoints) {
                    append("        ")
                    append(entry.functionName)
                    append(": ")
                    this.functionHeader(entry, skipAuth = true)
                    append(" => { return this.api.")
                    append(group.groupToPartName())
                    append(".")
                    functionCall(entry, skipAuth = false, authUsesThis = true, overrideUserType = userType)
                    appendLine(" }, ")
                }
                appendLine("    }")
            } else {
                appendLine("    readonly ${group.groupToPartName()} = {")
                for (entry in byGroup[group]!!) {
                    append("        ")
                    append(entry.functionName)
                    append(": ")
                    this.functionHeader(entry, skipAuth = true)
                    append(" => { return this.api.")
                    append(group.groupToPartName())
                    append(".")
                    functionCall(entry, skipAuth = false, authUsesThis = true, overrideUserType = userType)
                    appendLine(" }, ")
                }
                appendLine("    }")
            }


        }
        appendLine("}")
        appendLine()
    }

    appendLine()
    appendLine()
    appendLine()

    appendLine("export class LiveApi implements Api {")
    appendLine("    public constructor(public httpUrl: string, public socketUrl: string = httpUrl, public extraHeaders: Record<string, string> = {}, public responseInterceptors?: (x: Response)=>Response) {}")
    for (entry in byGroup[null]?.sortedBy { it.functionName } ?: listOf()) {
        append("    ")
        append(entry.functionName)
        this.functionHeader(entry, skipAuth = false)
        appendLine(" {")
        val hasInput = entry.inputType != Unit.serializer()
        appendLine("        return apiCall<${entry.inputType.write()}>(")
        appendLine("            `\${this.httpUrl}${entry.path.path.escaped}`,")
        appendLine("            ${if (hasInput) "input" else "undefined"},")
        appendLine("            {")
        appendLine("                method: \"${entry.route.method}\",")
        entry.primaryAuthName?.let {
            appendLine("                headers: ${it.userTypeTokenName()} ? { ...this.extraHeaders, \"Authorization\": `Bearer \${${it.userTypeTokenName()}}` } : this.extraHeaders,")
        }
        appendLine("            }, ")
        appendLine("            undefined,")
        appendLine("            this.responseInterceptors, ")
        entry.outputType.takeUnless { it == Unit.serializer() }?.let {
            appendLine("        ).then(x => x.json())")
        } ?: run {
            appendLine("        ).then(x => undefined)")
        }
        appendLine("    }")
    }
    for (group in groups.sortedBy { it.groupToPartName() }) {
        appendLine("    readonly ${group.groupToPartName()} = {")
        for (entry in byGroup[group]!!) {
            appendLine("        ")
            append("        ")
            append(entry.functionName)
            append(": ")
            this.functionHeader(entry, skipAuth = false)
            appendLine(" => {")
            val hasInput = entry.inputType != Unit.serializer()
            appendLine("            return apiCall<${entry.inputType.write()}>(")
            appendLine("                `\${this.httpUrl}${entry.path.path.escaped}`,")
            appendLine("                ${if (hasInput) "input" else "undefined"},")
            appendLine("                {")
            appendLine("                    method: \"${entry.route.method}\",")
            entry.primaryAuthName?.let {
                appendLine("                    headers: ${it.userTypeTokenName()} ? { ...this.extraHeaders, \"Authorization\": `Bearer \${${it.userTypeTokenName()}}` } : this.extraHeaders,")
            }
            appendLine("                }, ")
            appendLine("                undefined,")
            appendLine("                this.responseInterceptors, ")
            entry.outputType.takeUnless { it == Unit.serializer() }?.let {
                appendLine("            ).then(x => x.json())")
            } ?: run {
                appendLine("            ).then(x => undefined)")
            }
            appendLine("        },")
        }
        appendLine("    }")
    }
    appendLine("}")
    appendLine()
}


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
    "SortPart",
    "DataClassPath",
    "DataClassPathPartial",
    "QueryPartial",
)
private val skipFromLsPackage = setOf(
    "Partial",
) + fromLightningServerPackage

private fun String.groupToInterfaceName(): String = replaceFirstChar { it.uppercase() } + "Api"
private fun String.groupToPartName(): String = replaceFirstChar { it.lowercase() }

@Suppress("UNCHECKED_CAST")
private fun KType?.userTypeTokenName(): String = (this?.classifier as? KClass<Any>)?.userTypeTokenName() ?: "token"
private fun KClass<*>.userTypeTokenName(): String =
    simpleName?.replaceFirstChar { it.lowercase() }?.plus("Token") ?: "token"

private fun Appendable.functionHeader(
    documentable: Documentable,
    skipAuth: Boolean = false,
    overrideUserType: String? = null
) {
    append("(")
    var argComma = false
    arguments(documentable, skipAuth, overrideUserType).forEach {
        if (argComma) append(", ")
        else argComma = true
        append(it.name)
        if (it.optional) append("?")
        append(": ")
        it.type?.write()?.let { append(it) } ?: it.stringType?.let { append(it) }
    }
    append("): ")
    when (documentable) {
        is ApiEndpoint<*, *, *, *> -> {
            append("Promise<")
            documentable.outputType.write().let { append(it) }
            append(">")
        }

        is ApiWebsocket<*, *, *, *> -> {
            append("Observable<WebSocketIsh<")
            documentable.inputType.write().let { append(it) }
            append(", ")
            documentable.outputType.write().let { append(it) }
            append(">>")
        }

        else -> TODO()
    }
}

private fun Appendable.functionCall(
    documentable: Documentable,
    skipAuth: Boolean = false,
    authUsesThis: Boolean = false,
    overrideUserType: String? = null
) {
    append("${documentable.functionName}(")
    var argComma = false
    arguments(documentable, skipAuth, overrideUserType).forEach {
        if (argComma) append(", ")
        else argComma = true
        if (it.name == documentable.primaryAuthName?.userTypeTokenName() && authUsesThis) {
            append("this.${it.name}()")
        } else {
            append(it.name)
        }
    }
    append(")")
}

private data class TArg(
    val name: String,
    val type: KSerializer<*>? = null,
    val stringType: String? = null,
    val default: String? = null,
    val optional: Boolean = false
)

private fun arguments(
    documentable: Documentable,
    skipAuth: Boolean = false,
    overrideUserType: String? = null
): List<TArg> = when (documentable) {
    is ApiEndpoint<*, *, *, *> -> listOfNotNull(
        documentable.path.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>()
            .mapIndexed { index, it ->
                TArg(name = it.name, type = documentable.path.serializers[index], stringType = "String")
            },
        documentable.inputType.takeUnless { it == Unit.serializer() }?.let {
            TArg(name = "input", type = it)
        }?.let(::listOf),
        documentable.primaryAuthName?.takeUnless { skipAuth }?.let {
            TArg(
                name = (overrideUserType ?: it).userTypeTokenName(),
                stringType = "string",
                optional = !documentable.authOptions.options.contains(null).not()
            )
        }?.let(::listOf)
    ).flatten()

    is ApiWebsocket<*, *, *, *> -> listOfNotNull(
        documentable.primaryAuthName?.takeUnless { skipAuth }?.let {
            TArg(
                name = (overrideUserType ?: it).userTypeTokenName(),
                stringType = "string",
                optional = !documentable.authOptions.options.contains(null).not()
            )
        }?.let(::listOf),
        documentable.path.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>()
            .map {
                TArg(name = it.name, stringType = "string")
            }
    ).flatten()

    else -> TODO()
}


private fun KSerializer<*>.write(): String =
    nullElement()?.let { it.write() + " | null | undefined" } ?: when {
        this == Unit.serializer() -> "void"
        else -> StringBuilder().also { out ->
            when (descriptor.kind) {
                PrimitiveKind.BOOLEAN -> out.append("boolean")
                PrimitiveKind.BYTE,
                PrimitiveKind.SHORT,
                PrimitiveKind.INT,
                PrimitiveKind.LONG,
                PrimitiveKind.FLOAT,
                PrimitiveKind.DOUBLE -> out.append("number")

                PrimitiveKind.CHAR,
                PrimitiveKind.STRING -> {
                    val cleanName = this.descriptor.simpleSerialName
                    if (cleanName != "String") {
                        out.append(cleanName)
                        this.subSerializers().takeUnless { it.isEmpty() }?.joinToString(", ", "<", ">") { it.write() }
                            ?.let {
                                out.append(it)
                            }
                    } else {
                        out.append("string")
                    }
                }

                StructureKind.LIST -> {
                    out.append("Array<${this.listElement()!!.write()}>")
                }

                StructureKind.MAP -> {
                    out.append("Record")
                    listOf("string", this.mapValueElement()!!.write()).joinToString(", ", "<", ">").let {
                        out.append(it)
                    }
                }

                SerialKind.CONTEXTUAL -> {
                    this.uncontextualize().write().let { out.append(it) }
                }

                is PolymorphicKind,
                StructureKind.OBJECT,
                SerialKind.ENUM,
                StructureKind.CLASS -> {
                    if (descriptor.serialName == "com.lightningkite.serialization.Partial") {
                        out.append("DeepPartial")
                    } else {
                        out.append(descriptor.simpleSerialName)
                    }
                    this.tryTypeParameterSerializers2()?.takeUnless { it.isEmpty() }
                        ?.joinToString(", ", "<", ">") {
                            it.write()
                        }?.let {
                            out.append(it)
                        }
                }
            }
        }.toString().replace("/loose", "")
    }

private fun KSerializer<*>.getGenerics(): Array<KSerializer<*>>? {
    if (descriptor.kind is PolymorphicKind ||
        descriptor.kind is StructureKind.OBJECT ||
        descriptor.kind is SerialKind.ENUM ||
        descriptor.kind is StructureKind.CLASS
    ) {
        return this.tryTypeParameterSerializers2()
    }
    return null
}

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


val autoGeneratedNames = listOf(
    "default",
    "query",
    "queryPartial",
    "detail",
    "insertBulk",
    "insert",
    "upsert",
    "bulkReplace",
    "replace",
    "bulkModify",
    "modifyWithDiff",
    "modify",
    "simplifiedModify",
    "bulkDelete",
    "delete",
    "count",
    "groupCount",
    "aggregate",
    "groupAggregate",
)

fun List<ApiEndpoint<*, *, *, *>>?.hasAutoEndpoints(): Boolean = this?.let {
    val hasAllEndpoints =
        autoGeneratedNames.all { auto -> this.firstOrNull { it.functionName == auto } != null }

    val detailIsValid = this.firstOrNull {
        if (it.functionName == "detail" && arguments(it).isNotEmpty()) {
            (arguments(it).firstOrNull { it.type?.write() == "UUID" } != null) && arguments(it).size == 2
        } else false
    } != null

    val deleteIsValid = this.firstOrNull {
        if (it.functionName == "delete") {
            it.outputType.write() == "void"
        } else false
    } != null

    return hasAllEndpoints && detailIsValid && deleteIsValid
} ?: false