package com.lightningkite.ktordb

import com.google.devtools.ksp.symbol.*

data class MongoFields(
    val declaration: KSClassDeclaration
) {
    val packageName: String get() = declaration.packageName.asString()
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
        declaration.containingFile?.annotations?.forEach {
            when (it.shortName.asString()) {
                "UseContextualSerialization" -> {
                    appendLine(
                        "@file:${it.shortName.asString()}(${
                            it.arguments.first().let { argument ->
                                argument.value
                                    ?.toString()
                                    ?.removePrefix("[")
                                    ?.removeSuffix("]")
                                    ?.split(", ")
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
        appendLine()
        appendLine("package ${packageName}")
        appendLine()
        declaration.containingFile?.ktFile?.importList?.imports
            ?.map { it.importPath.toString() }
            ?.plus(
                listOf(
                    "com.lightningkite.ktordb.*",
                    "kotlinx.serialization.*",
                    "java.time.*",
                    "java.util.*",
                )
            )
            ?.distinct()
            ?.forEach { appendLine("import $it") }
        appendLine()
        appendLine("object ${simpleName}Fields {")
        tab {
            for (field in fields) {
                if(comparable.asStarProjectedType().isAssignableFrom(field.kotlinType.resolve())) {
                    appendLine("val ${field.name} = DataClassProperty<$simpleName, ${field.kotlinType.toKotlin()}>(\"${field.name}\", {it.${field.name}}, {it, v -> it.copy(${field.name} = v)}, compareBy { it.${field.name} })")
                } else {
                    appendLine("val ${field.name} = DataClassProperty<$simpleName, ${field.kotlinType.toKotlin()}>(\"${field.name}\", {it.${field.name}}, {it, v -> it.copy(${field.name} = v)})")
                }
            }
            appendLine("init {")
            tab {
                appendLine("$simpleName.serializer().fields = mapOf(${fields.joinToString { """"${it.name}" to ${it.name} """ }})")
            }
            appendLine("}")
        }
        appendLine("}")
        appendLine()
        for (field in fields) {
            appendLine("val <K> PropChain<K, $simpleName>.${field.name}: PropChain<K, ${field.kotlinType.toKotlin()}> get() = this[${simpleName}Fields.${field.name}]")
        }
        appendLine("val ${simpleName}.Companion.chain: PropChain<$simpleName, $simpleName> get() = startChain()")
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
