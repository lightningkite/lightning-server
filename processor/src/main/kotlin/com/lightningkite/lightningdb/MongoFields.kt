package com.lightningkite.lightningdb

import com.google.devtools.ksp.symbol.*
import org.jetbrains.kotlin.util.capitalizeDecapitalize.decapitalizeAsciiOnly

data class MongoFields(
    val declaration: KSClassDeclaration
) {
    val packageName: String get() = declaration.packageName.asString()
    val typeReference: String get() = declaration.safeLocalReference() + (declaration.typeParameters.takeUnless { it.isEmpty() }?.joinToString(", ", "<", ">") { it.name.asString() } ?: "")
    val classReference: String get() = declaration.safeLocalReference()
    val simpleName: String get() = declaration.simpleName.getShortName()
    val fields by lazy { declaration.fields() }
    val hasId by lazy { declaration.superTypes.any { it.resolve().declaration.qualifiedName?.asString() == "com.lightningkite.lightningdb.HasId" } }

    fun write(out: TabAppendable) = with(out) {
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
                                    ?.joinToString { "$it::class" }
                            }
                        })"
                    )
                }
                else -> {
                    try {
                        if(it.shortName.asString() == "SharedCode") return@forEach
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
        if(packageName.isNotEmpty()) appendLine("package ${packageName}")
        appendLine()
        declaration.containingFile?.ktFile?.importList?.imports
            ?.map { it.importPath.toString() }
            ?.plus(
                listOf(
                    "com.lightningkite.lightningdb.*",
                    "kotlin.reflect.*",
                    "kotlinx.serialization.*",
                    "kotlinx.serialization.builtins.*",
                    "kotlinx.serialization.internal.GeneratedSerializer",
                    "kotlinx.datetime.*",
                    "com.lightningkite.*",
                )
            )
            ?.distinct()
            ?.forEach { appendLine("import $it") }
        appendLine()
        val contextualTypes = declaration.containingFile?.annotation("UseContextualSerialization", "kotlinx.serialization")?.arguments?.firstOrNull()
            ?.value
            ?.let { it as? List<KSType> }
            ?.map { it.declaration }
            ?: listOf()
        appendLine("// Contextual types: ${contextualTypes.joinToString { it.qualifiedName?.asString() ?: "-" }}")
        if(declaration.typeParameters.isEmpty()) {
            appendLine("fun prepare${simpleName}Fields() {")
            tab {
                appendLine("val props: Array<SerializableProperty<$classReference, *>> = arrayOf(${fields.joinToString { field -> "${simpleName}_${field.name}" }})")
                appendLine("$classReference.serializer().properties { props }")
            }
            appendLine("}")
            for (field in fields) {
                appendLine("val <K> DataClassPath<K, $typeReference>.${field.name}: DataClassPath<K, ${field.kotlinType.toKotlin()}> get() = this[${classReference}_${field.name}]")
            }
            appendLine("inline val $typeReference.Companion.path: DataClassPath<$typeReference, $typeReference> get() = path<$typeReference>()")
            appendLine()
            appendLine()
            for (field in fields) {
                appendLine("object ${simpleName}_${field.name}: SerializableProperty<$typeReference, ${field.kotlinType.toKotlin()}> {")
                tab {
                    appendLine("""override val name: String = "${field.name}"""")
                    appendLine("""override fun get(receiver: $typeReference): ${field.kotlinType.toKotlin()} = receiver.${field.name}""")
                    appendLine("""override fun setCopy(receiver: $typeReference, value: ${field.kotlinType.toKotlin()}) = receiver.copy(${field.name} = value)""")
                    appendLine("""override val serializer: KSerializer<${field.kotlinType.toKotlin()}> = ${field.kotlinType.resolve()!!.toKotlinSerializer(contextualTypes)}""")
                }
                appendLine("}")
            }
        } else {
            appendLine("fun prepare${simpleName}Fields() {")
            tab {
                val nothings = declaration.typeParameters.joinToString(", ") { "NothingSerializer()" }
                appendLine("$classReference.serializer($nothings).properties { args -> arrayOf(")
                tab {
                    val args = declaration.typeParameters.indices.joinToString(", ") { "args[$it]" }
                    for(field in fields) {
                        appendLine("${simpleName}_${field.name}($args),")
                    }
                }
                appendLine(") }")
            }
            appendLine("}")
            for (field in fields) {
                appendLine("inline val <ROOT, ${declaration.typeParameters.joinToString(", ") { it.name.asString() + ": " + (it.bounds.firstOrNull()?.toKotlin() ?: "Any?") }}> DataClassPath<ROOT, $typeReference>.${field.name}: DataClassPath<ROOT, ${field.kotlinType.toKotlin()}> get() = this.serializer.tryTypeParameterSerializers()!!.let { this[${simpleName}_${field.name}(${declaration.typeParameters.withIndex().joinToString(", ") { "it[${it.index}] as KSerializer<${it.value.name.asString()}>" }})] }")
            }
            appendLine()
            appendLine()
            for (field in fields) {
                appendLine("class ${simpleName}_${field.name}<${declaration.typeParameters.joinToString(", ") { it.name.asString() + ": " + (it.bounds.firstOrNull()?.toKotlin() ?: "Any?") }}>(${declaration.typeParameters.joinToString(", ") { it.name.asString().decapitalizeAsciiOnly() + ": KSerializer<${it.name.asString()}>" }}): SerializableProperty<$typeReference, ${field.kotlinType.toKotlin()}> {")
                tab {
                    appendLine("""override val name: String = "${field.name}"""")
                    appendLine("""override fun get(receiver: $typeReference): ${field.kotlinType.toKotlin()} = receiver.${field.name}""")
                    appendLine("""override fun setCopy(receiver: $typeReference, value: ${field.kotlinType.toKotlin()}) = receiver.copy(${field.name} = value)""")
                    appendLine("""override val serializer: KSerializer<${field.kotlinType.toKotlin()}> = ${field.kotlinType.resolve()!!.toKotlinSerializer(contextualTypes)}""")
                    appendLine("""override fun hashCode(): Int = ${field.name.hashCode() * 31 + simpleName.hashCode()}""")
                    appendLine("""override fun equals(other: Any?): Boolean = other is ${simpleName}_${field.name}<${declaration.typeParameters.joinToString(", ") { "* "}}>""")
                }
                appendLine("}")
            }
        }
    }
    fun writeTs(out: TabAppendable) {
        out.appendLine("---")
        for (field in fields) {
            out.appendLine("- id: ${packageName}.${field.name}")
            out.appendLine("  type: get")
            out.appendLine("  receiver: com.lightningkite.lightningdb.DataClassPath<*, ${packageName}.${typeReference}>")
            out.appendLine("  template: '~this~.prop(\"${field.name}\")'")
        }
    }
    fun writeSwift(out: TabAppendable) {
        out.appendLine("---")
        for (field in fields) {
            out.appendLine("- id: ${packageName}.${field.name}")
            out.appendLine("  type: get")
            out.appendLine("  receiver: com.lightningkite.lightningdb.DataClassPath<*, ${packageName}.${typeReference}>")
            out.appendLine("  template: '~this~.get(prop: ${typeReference}.${field.name}Prop)'")
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
            "com.lightningkite.lightningdb.UUIDFor",
            "java.util.UUID",
            "com.lightningkite.UUID",
            "kotlinx.datetime.Instant",
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
            "com.lightningkite.UUID",
            "kotlinx.datetime.Instant",
            "com.lightningkite.lightningdb.UUIDFor",
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
            "com.lightningkite.UUID",
            "com.lightningkite.lightningdb.UUIDFor",
            "kotlinx.datetime.Instant",
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
