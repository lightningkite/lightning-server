package com.lightningkite.lightningdb

import com.google.devtools.ksp.symbol.*
import com.lightningkite.lightningserver.db.imports
import org.jetbrains.kotlin.util.capitalizeDecapitalize.decapitalizeAsciiOnly
import kotlin.reflect.KClass

fun KSClassDeclaration.handleSerializableAnno(out: TabAppendable) {
    try {
        out.appendLine(
            """
            SerializableAnnotation.parser<${qualifiedName?.asString()}>("${qualifiedName?.asString()}") { SerializableAnnotation("${qualifiedName?.asString()}", values = mapOf(${
                (this.primaryConstructor?.parameters ?: listOf()).mapNotNull { it.name }
                    .joinToString { "\"${it.asString()}\" to SerializableAnnotationValue(it.${it.asString()})" }
            })) }
        """.trimIndent()
        )
    } catch (e: Exception) {
        out.appendLine("/* ${e.stackTraceToString()} */")
    }
}

data class MongoFields(
    val declaration: KSClassDeclaration
) {
    val packageName: String get() = declaration.packageName.asString()
    val typeReference: String
        get() = declaration.safeLocalReference() + (declaration.typeParameters.takeUnless { it.isEmpty() }
            ?.joinToString(", ", "<", ">") { it.name.asString() } ?: "")
    val classReference: String get() = declaration.safeLocalReference()
    val simpleName: String get() = declaration.simpleName.getShortName()
    val fields by lazy { declaration.fields() }
    val hasId by lazy { declaration.superTypes.any { it.resolve().declaration.qualifiedName?.asString() == "com.lightningkite.lightningserver.db.HasId" } }

    fun write(out: TabAppendable) = with(out) {
        appendLine("""// Automatically generated based off ${declaration.containingFile?.fileName}""")
        appendLine("""@file:OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)""")
        if (declaration.typeParameters.isEmpty())
            appendLine("""@file:Suppress("UnusedImport", "UNCHECKED_CAST")""")
        else
            appendLine("""@file:Suppress("UNCHECKED_CAST", "UNUSED_PARAMETER", "UnusedImport")""")
        appendLine()
        if (packageName.isNotEmpty()) appendLine("package ${packageName}")
        appendLine()
        try {
            declaration.containingFile?.imports
                ?.plus(
                    listOf(
                        "com.lightningkite.serialization.*",
                        "com.lightningkite.serialization.DataClassPath",
                        "com.lightningkite.serialization.DataClassPathSelf",
                        "com.lightningkite.serialization.SerializableProperty",
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
                ?.filter { it.substringAfterLast('.').let {
                    !(it.startsWith("prepare") && it.endsWith("Fields")
                            || it.startsWith("prepareModels"))
                } }
                ?.forEach { appendLine("import $it") }
        } catch(e: Exception) {
            appendLine("/*" + e.stackTraceToString() + "*/")
        }
        appendLine()
        val contextualTypes = declaration.containingFile?.annotation(
            "UseContextualSerialization",
            "kotlinx.serialization"
        )?.arguments?.firstOrNull()
            ?.value
            ?.let {
                @Suppress("UNCHECKED_CAST")
                it as? List<KSType>
            }
            ?.map { it.declaration }
            ?: listOf()
        appendLine("// Contextual types: ${contextualTypes.joinToString { it.qualifiedName?.asString() ?: "-" }}")
        if (declaration.typeParameters.isEmpty()) {
            appendLine("fun prepare${simpleName}Fields() {")
            tab {
                appendLine("val props: Array<SerializableProperty<$classReference, *>> = arrayOf(${fields.joinToString { field -> "${simpleName}_${field.name}" }})")
                appendLine("$classReference.serializer().properties { props }")
            }
            appendLine("}")
            for (field in fields) {
                appendLine("val <K> DataClassPath<K, $typeReference>.${field.name}: DataClassPath<K, ${field.kotlinType.toKotlin()}> get() = this[${simpleName}_${field.name}]")
            }
            appendLine("inline val $typeReference.Companion.path: DataClassPath<$typeReference, $typeReference> get() = path<$typeReference>()")
            appendLine()
            appendLine()
            for ((index, field) in fields.withIndex()) {
                appendLine("object ${simpleName}_${field.name}: SerializableProperty<$typeReference, ${field.kotlinType.toKotlin()}> {")
                tab {
                    appendLine("""override val name: String by lazy { $classReference.serializer().descriptor.getElementName($index) }""")
                    appendLine("""override fun get(receiver: $typeReference): ${field.kotlinType.toKotlin()} = receiver.${field.name}""")
                    appendLine("""override fun setCopy(receiver: $typeReference, value: ${field.kotlinType.toKotlin()}) = receiver.copy(${field.name} = value)""")
                    appendLine(
                        """override val serializer: KSerializer<${field.kotlinType.toKotlin()}> by lazy { ($classReference.serializer() as GeneratedSerializer<*>).childSerializers()[$index] as KSerializer<${field.kotlinType.toKotlin()}> }"""
                    )
                    appendLine("""override val annotations: List<Annotation> by lazy { $classReference.serializer().descriptor.getElementAnnotations($index) }""")
                    field.default?.let {
                        appendLine("""override val default: ${field.kotlinType.toKotlin()} = $it""")
                        appendLine("""override val defaultCode: String? = "${it.substringBefore('\n').trim().replace("$", "\\$").replace("\"", "\\\"")}" """)
                    }
                }
                appendLine("}")
            }
        } else {
            val nothings = declaration.typeParameters.joinToString(", ") { "NothingSerializer()" }
            appendLine("fun prepare${simpleName}Fields() {")
            tab {
                appendLine("$classReference.serializer($nothings).properties { args -> arrayOf(")
                tab {
                    val args = declaration.typeParameters.indices.joinToString(", ") { "args[$it]" }
                    for (field in fields) {
                        appendLine("${simpleName}_${field.name}($args),")
                    }
                }
                appendLine(") }")
            }
            appendLine("}")
            for (field in fields) {
                appendLine(
                    "inline val <ROOT, ${
                        declaration.typeParameters.joinToString(", ") {
                            it.name.asString() + ": " + (it.bounds.firstOrNull()?.toKotlin() ?: "Any?")
                        }
                    }> DataClassPath<ROOT, $typeReference>.${field.name}: DataClassPath<ROOT, ${field.kotlinType.toKotlin()}> get() = this.serializer.tryTypeParameterSerializers()!!.let { this[${simpleName}_${field.name}(${
                        declaration.typeParameters.withIndex()
                            .joinToString(", ") { "it[${it.index}] as KSerializer<${it.value.name.asString()}>" }
                    })] }"
                )
            }
            appendLine()
            appendLine()
            for ((index, field) in fields.withIndex()) {
                appendLine(
                    "class ${simpleName}_${field.name}<${
                        declaration.typeParameters.joinToString(", ") {
                            it.name.asString() + ": " + (it.bounds.firstOrNull()?.toKotlin() ?: "Any?")
                        }
                    }>(${
                        declaration.typeParameters.joinToString(", ") {
                            it.name.asString().decapitalizeAsciiOnly() + ": KSerializer<${it.name.asString()}>"
                        }
                    }): SerializableProperty<$typeReference, ${field.kotlinType.toKotlin()}> {"
                )
                tab {
                    appendLine("""override val name: String by lazy { $classReference.serializer($nothings).descriptor.getElementName($index) }""")
                    appendLine("""override fun get(receiver: $typeReference): ${field.kotlinType.toKotlin()} = receiver.${field.name}""")
                    appendLine("""override fun setCopy(receiver: $typeReference, value: ${field.kotlinType.toKotlin()}) = receiver.copy(${field.name} = value)""")
                    appendLine(
                        """override val serializer: KSerializer<${field.kotlinType.toKotlin()}> by lazy { ($classReference.serializer(${declaration.typeParameters.joinToString(", "){ it.name.asString().decapitalizeAsciiOnly() }}) as GeneratedSerializer<*>).childSerializers()[$index] as KSerializer<${field.kotlinType.toKotlin()}> }"""
                    )
                    appendLine("""override val annotations: List<Annotation> by lazy { $classReference.serializer(${declaration.typeParameters.joinToString(", "){ it.name.asString().decapitalizeAsciiOnly() }}).descriptor.getElementAnnotations($index) }""")
                    field.default?.let {
                        appendLine("""override val default: ${field.kotlinType.toKotlin()} = $it""")
                        appendLine("""override val defaultCode: String? = "${it.substringBefore('\n').trim().replace("$", "\\$").replace("\"", "\\\"")}" """)
                    }
                    appendLine("""override fun hashCode(): Int = ${field.name.hashCode() * 31 + simpleName.hashCode()}""")
                    appendLine(
                        """override fun equals(other: Any?): Boolean = other is ${simpleName}_${field.name}<${
                            declaration.typeParameters.joinToString(
                                ", "
                            ) { "* " }
                        }>"""
                    )
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
            "com.lightningkite.UUID",
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
            "com.lightningkite.UUID",
            "com.lightningkite.UUID",
            "kotlinx.datetime.Instant",
            "com.lightningkite.lightningdb.UUIDFor",
            "kotlin.Char" -> "ComparableCondition" + "<${this.makeNotNullable().toKotlin()}>"

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
                this.makeNotNullable().toKotlin()
            }>"

            else -> {
                if ((declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS) "EquatableCondition<$name>" else "${name}Condition"
            }
        }.let {
            if (isMarkedNullable) "NullableCondition<${this.toKotlin()}, ${
                this.makeNotNullable().toKotlin()
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
                this.makeNotNullable().toKotlin()
            }>"

            else -> {
                if ((declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS) "EquatableModification<$name>" else "${name}Modification"
            }
        }.let {
            if (isMarkedNullable) "NullableModification<${this.toKotlin()}, ${
                this.makeNotNullable().toKotlin()
            }, $it>"
            else it
        }
    }

private fun ResolvedAnnotation.writeSerialzable(): String {
    return "SerializableAnnotation(fqn = \"${this.type.qualifiedName?.asString()}\", values = mapOf(${this.arguments.entries.joinToString { "\"${it.key}\" to \"${it.value.jsonRender()}\"" }}))"
}

private fun Any?.jsonRender(): String {
    return when (this) {
        is KClass<*> -> "\"" + (this.qualifiedName) + "\""
        is KSType -> "\"" + (this.declaration?.qualifiedName?.asString() ?: "") + "\""
        is KSTypeReference -> "\"" + (this.tryResolve()?.declaration?.qualifiedName?.asString() ?: "") + "\""
        is KSClassDeclaration -> "\"" + (this.qualifiedName?.asString() ?: "") + "\""
        is Array<*> -> joinToString(", ", "[", "]") { it.jsonRender() }
        is String -> "\"$this\""
        null -> "null"
//        else -> "$this (${this::class})"
        else -> toString()
    }.replace("\"", "\\\"")
}