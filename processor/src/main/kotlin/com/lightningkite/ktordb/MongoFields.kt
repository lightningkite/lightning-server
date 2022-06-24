package com.lightningkite.ktordb

import com.google.devtools.ksp.symbol.*

data class MongoFields(
    val declaration: KSClassDeclaration
) {
    val packageName: String get() = declaration.packageName.asString()
    val typeReference: String get() = declaration.safeLocalReference() + (declaration.typeParameters.takeUnless { it.isEmpty() }?.joinToString(", ", "<", ">") { it.name.asString() } ?: "")
    val classReference: String get() = declaration.safeLocalReference()
    val simpleName: String get() = declaration.simpleName.getShortName()
    val fields by lazy { declaration.fields() }
    val hasId by lazy { declaration.superTypes.any { it.resolve().declaration.qualifiedName?.asString() == "com.lightningkite.ktordb.HasId" } }

    fun allSubs(handled: MutableSet<KSClassDeclaration>): Sequence<MongoFields> = sequenceOf(this) + fields
        .flatMap { it.kotlinType.resolve().allClassDeclarations() }
        .filter { it.usesSub && it.classKind != ClassKind.ENUM_CLASS }
        .filter { handled.add(it) }
        .asSequence()
        .map { MongoFields(it) }
        .flatMap { it.allSubs(handled) }

    fun write(out: TabAppendable) = with(out) {
        var useContextualFor = listOf<String>()
        declaration.containingFile?.annotations?.forEach {
            when (it.shortName.asString()) {
                "UseContextualSerialization" -> {
                    it.resolve().arguments
                    appendLine(
                        "@file:${it.shortName.asString()}(${
                            it.arguments.first().let { argument ->
                                argument.value
                                    ?.toString()
                                    ?.removePrefix("[")
                                    ?.removeSuffix("]")
                                    ?.split(", ")
                                    ?.also { useContextualFor = it }
                                    ?.joinToString { "$it::class" }
                            }
                        })"
                    )
                }
                else -> {
                    try {
                        if(it.shortName.asString() == "SharedCode") khrysalisUsed = true
                        appendLine(
                            "@file:${it.shortName.asString()}(${
                                it.arguments.joinToString(", ") {
                                    when (val v = it.value) {
                                        is List<*> -> v.joinToString { it.toString() }
                                    }
                                    it.value.toString()
                                }
                            })"
                        )
                    } catch (e: Exception) {
                        throw Exception("Failed to generate file annotations for file $simpleName and annotation ${it.shortName}")
                    }
                }
            }
        }
        appendLine("@file:OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)")
        appendLine()
        appendLine("package ${packageName}")
        appendLine()
        declaration.containingFile?.ktFile?.importList?.imports
            ?.map { it.importPath.toString() }
            ?.plus(
                listOf(
                    "com.lightningkite.ktordb.*",
                    "kotlin.reflect.*",
                    "kotlinx.serialization.*",
                    "kotlinx.serialization.builtins.*",
                    "kotlinx.serialization.internal.GeneratedSerializer",
                    "java.time.*",
                    "java.util.*",
                )
            )
            ?.distinct()
            ?.forEach { appendLine("import $it") }
        appendLine()
        if(declaration.typeParameters.isEmpty()) {
            appendLine("fun prepare${simpleName}Fields() {")
            tab {
                for (field in fields) {
                    appendLine("$classReference::${field.name}.setCopyImplementation { original, value -> original.copy(${field.name} = value) }")
                }
            }
            appendLine("}")
            for (field in fields) {
                appendLine("val <K> PropChain<K, $typeReference>.${field.name}: PropChain<K, ${field.kotlinType.toKotlin()}> get() = this[${classReference}::${field.name}]")
            }
        } else {
            appendLine("fun prepare${simpleName}Fields() {")
            tab {
                for (field in fields) {
                    appendLine("$classReference<${declaration.typeParameters.joinToString(", ") { it.bounds.firstOrNull()?.toKotlin() ?: "Any?" }}>::${field.name}.setCopyImplementation { original, value -> original.copy(${field.name} = value) }")
                }
            }
            appendLine("}")
            for (field in fields) {
                appendLine("inline val <ROOT, ${declaration.typeParameters.joinToString(", ") { "reified " + it.name.asString() }}> PropChain<ROOT, $typeReference>.${field.name}: PropChain<ROOT, ${field.kotlinType.toKotlin()}> get() = this[${classReference}${declaration.typeParameters.joinToString(", ", "<", ">") { it.name.asString() }}::${field.name}]")
            }
        }
    }
}

private val KSType.useCustomType: Boolean
    get() {
        val actualDeclaration = declaration.findClassDeclaration()
        return when (actualDeclaration?.qualifiedName?.asString()) {
            "kotlin.Byte",
            "kotlin.Short",
            "kotlin.Int",
            "kotlin.Long",
            "kotlin.Float",
            "kotlin.Double",
            "kotlin.String",
            "kotlin.collections.List",
            "kotlin.collections.Map",
            "kotlin.Boolean",
            "kotlin.Pair",
            "com.lightningkite.ktordb.UUIDFor",
            "java.util.UUID",
            "java.time.Instant",
            "org.litote.kmongo.Id" -> false
            else -> true
        }
    }

private val KSType.conditionType: String
    get() {
        val actualDeclaration = declaration.findClassDeclaration()
        return when (val name = actualDeclaration?.qualifiedName?.asString()) {
            "kotlin.Int" -> "IntBitwiseComparableCondition"
            "kotlin.Long" -> "LongBitwiseComparableCondition"
            "kotlin.String" -> "TextCondition"
            "kotlin.Byte",
            "kotlin.Short",
            "kotlin.Float",
            "kotlin.Double",
            "java.util.UUID",
            "java.time.Instant",
            "com.lightningkite.ktordb.UUIDFor",
            "kotlin.Char" -> "ComparableCondition" + "<${this.makeNotNullable().toKotlin(annotations)}>"
            "kotlin.collections.List" -> "ArrayCondition" + "<${
                this.arguments[0].run {
                    "${
                        type!!.resolve().toKotlin(annotations)
                    }, ${type!!.resolve().conditionType}"
                }
            }>"
            "kotlin.collections.Map" -> "MapCondition" + "<${
                this.arguments[1].run {
                    "${
                        type!!.resolve().toKotlin(annotations)
                    }, ${type!!.resolve().conditionType}"
                }
            }>"
            "kotlin.Boolean", "org.litote.kmongo.Id", "kotlin.Pair" -> "EquatableCondition" + "<${
                this.makeNotNullable().toKotlin(annotations)
            }>"
            else -> {
                if ((declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS) "EquatableCondition<$name>" else "${name}Condition"
            }
        }.let {
            if (isMarkedNullable) "NullableCondition<${this.toKotlin()}, ${
                this.makeNotNullable().toKotlin(annotations)
            }, $it>"
            else it
        }
    }

private val KSType.modificationType: String
    get() {
        val actualDeclaration = declaration.findClassDeclaration()
        return when (val name = actualDeclaration?.qualifiedName?.asString()) {
            "kotlin.Byte",
            "kotlin.Short",
            "kotlin.Int",
            "kotlin.Long",
            "kotlin.Float",
            "kotlin.Double" -> "NumberModification" + "<${this.makeNotNullable().toKotlin(annotations)}>"
            "java.util.UUID",
            "com.lightningkite.ktordb.UUIDFor",
            "java.time.Instant",
            "kotlin.String", "kotlin.Char" -> "ComparableModification" + "<${
                this.makeNotNullable().toKotlin(annotations)
            }>"
            "kotlin.collections.List" -> "ArrayModification" + "<${
                this.arguments[0].run {
                    "${
                        type!!.resolve().toKotlin(annotations)
                    }, ${type!!.resolve().conditionType}, ${type!!.resolve().modificationType}"
                }
            }>"
            "kotlin.collections.Map" -> "MapModification" + "<${
                this.arguments[1].run {
                    "${
                        type!!.resolve().toKotlin(annotations)
                    }, ${type!!.resolve().modificationType}"
                }
            }>"
            "kotlin.Boolean", "org.litote.kmongo.Id", "kotlin.Pair" -> "EquatableModification" + "<${
                this.makeNotNullable().toKotlin(annotations)
            }>"
            else -> {
                if ((declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS) "EquatableModification<$name>" else "${name}Modification"
            }
        }.let {
            if (isMarkedNullable) "NullableModification<${this.toKotlin(annotations)}, ${
                this.makeNotNullable().toKotlin(annotations)
            }, $it>"
            else it
        }
    }
