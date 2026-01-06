package com.lightningkite.lightningserver.ai

import com.lightningkite.services.data.Description
import com.lightningkite.services.database.innerElement
import com.lightningkite.services.database.innerElement2
import com.lightningkite.services.database.nullElement
import com.lightningkite.services.database.serializableProperties
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.capturedKClass
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.modules.SerializersModule
import kotlin.reflect.KClass
import com.lightningkite.lightningserver.ai.models.*

public fun KSerializer<*>.explain(module: SerializersModule): String = buildString {
    // We explain it in Typescript syntax since there's tons of training data for that in these LLMs.

    val queue = ArrayList<KSerializer<*>>()
    val handled = HashSet<KSerializer<*>>()

    appendLine(toTypescriptTypeRef(module) {
        val toAdd = it.nullElement() ?: it
        if(handled.add(toAdd)) queue.add(toAdd)
    })
    appendLine()
    while(queue.isNotEmpty()) {
        val next = queue.removeFirst()
        appendLine(next.explainSingle(module) {
            val toAdd = it.nullElement() ?: it
            if(handled.add(toAdd)) queue.add(toAdd)
        } ?: "unknown")
        appendLine()
    }
}
private fun KSerializer<*>.explainSingle(module: SerializersModule, onFurtherExplanationNeeded: (KSerializer<*>)->Unit): String? {
    fun StringBuilder.comment() {
        val descriptions = descriptor.annotations.filterIsInstance<Description>()
        if(descriptions.isEmpty()) return
        val lines = descriptions.joinToString("\n"){it.text}.lines().joinToString("\n") { " * $it" }
        if(lines.isNotEmpty()) {
            appendLine("/**")
            appendLine(lines)
            appendLine("**/")
        }
    }
    return when(descriptor.kind) {
        is PrimitiveKind -> null
        SerialKind.CONTEXTUAL ->
            (
                    descriptor.capturedKClass
                        ?: throw IllegalArgumentException("Could not find capturedKClass for ${descriptor.serialName}'s serial descriptor")
                    ).let {
                    @Suppress("UNCHECKED_CAST")
                    module.getContextual<Any>(it as KClass<Any>)
                        ?: throw IllegalArgumentException("Could not find contextual implementation for ${descriptor.serialName}")
                }
                .explainSingle(module, onFurtherExplanationNeeded)
        SerialKind.ENUM -> null
        StructureKind.CLASS -> buildString {
            comment()
            if(descriptor.isInline) {
                appendLine("type ${descriptor.serialName.substringBefore('/').substringAfterLast('.')} = ${innerElement().toTypescriptTypeRef(module, onFurtherExplanationNeeded)}")
            } else {
                appendLine("interface ${descriptor.serialName.substringBefore('/').substringAfterLast('.')} {")
                serializableProperties?.forEach { prop ->
                    appendLine("    ${prop.name}: ${prop.serializer.toTypescriptTypeRef(module, onFurtherExplanationNeeded)}")
                }
                appendLine("}")
            }
        }
        StructureKind.LIST -> null
        StructureKind.MAP -> null
        StructureKind.OBJECT -> "{}"
        else -> "unknown"
    }
}
private fun KSerializer<*>.toTypescriptTypeRef(module: SerializersModule, onFurtherExplanationNeeded: (KSerializer<*>)->Unit): String {
    // We explain it in Typescript-like syntax since there's tons of training data for that in these LLMs.

    return when(descriptor.kind) {
        PrimitiveKind.BOOLEAN -> "boolean"
        PrimitiveKind.CHAR -> "char"
        PrimitiveKind.DOUBLE -> "number"
        PrimitiveKind.FLOAT -> "number"
        PrimitiveKind.INT -> "int"
        PrimitiveKind.LONG -> "long"
        PrimitiveKind.SHORT -> "short"
        PrimitiveKind.BYTE -> "byte"
        PrimitiveKind.STRING -> "string"
        SerialKind.CONTEXTUAL ->
            (
                    descriptor.capturedKClass
                        ?: throw IllegalArgumentException("Could not find capturedKClass for ${descriptor.serialName}'s serial descriptor")
                    ).let {
                    @Suppress("UNCHECKED_CAST")
                    module.getContextual<Any>(it as KClass<Any>)
                        ?: throw IllegalArgumentException("Could not find contextual implementation for ${descriptor.serialName}")
                }
                .toTypescriptTypeRef(module, onFurtherExplanationNeeded)
        SerialKind.ENUM -> descriptor.elementNames.joinToString(" | ") { "\"$it\"" }
        StructureKind.CLASS -> {
            onFurtherExplanationNeeded(this)
            descriptor.serialName.substringBefore('/').substringAfterLast('.')
        }
        StructureKind.LIST -> "Array<${innerElement().toTypescriptTypeRef(module, onFurtherExplanationNeeded)}>"
        StructureKind.MAP -> "Record<string, ${innerElement2().toTypescriptTypeRef(module, onFurtherExplanationNeeded)}>"
        StructureKind.OBJECT -> "{}"
        else -> "unknown"
    } + (if(descriptor.isNullable) " | null" else "")
}