package com.lightningkite.lightningserver.ai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.descriptors.*

internal fun ToolParameterType.asValueTool(name: String, description: String, valueDescription: String? = null) =
    ToolDescriptor(
        name = name,
        description = description,
        requiredParameters = listOf(
            ToolParameterDescriptor(
                name = "__wrapped_value__",
                description = valueDescription ?: "",
                this
            )
        )
    )

internal fun SerialDescriptor.lsAsToolDescriptor(
    toolName: String,
    toolDescription: String? = null,
    valueDescription: String? = null,
    maxDepth: Int = Int.MAX_VALUE,
): ToolDescriptor {
    val description = toolDescription ?: annotations.filterIsInstance<LLMDescription>().firstOrNull()?.description ?: ""

    return when (kind) {
        PrimitiveKind.STRING -> ToolParameterType.String.asValueTool(toolName, description, valueDescription)
        PrimitiveKind.BOOLEAN -> ToolParameterType.Boolean.asValueTool(toolName, description, valueDescription)
        PrimitiveKind.CHAR -> ToolParameterType.String.asValueTool(toolName, description, valueDescription)
        PrimitiveKind.BYTE,
        PrimitiveKind.SHORT,
        PrimitiveKind.INT,
        PrimitiveKind.LONG,
            -> ToolParameterType.Integer.asValueTool(toolName, description, valueDescription)

        PrimitiveKind.FLOAT,
        PrimitiveKind.DOUBLE,
            -> ToolParameterType.Float.asValueTool(toolName, description, valueDescription)

        StructureKind.LIST -> ToolParameterType.List(
            getElementDescriptor(0).toToolParameterType(maxDepth, 0)
        ).asValueTool(toolName, description, valueDescription)

        SerialKind.ENUM -> ToolParameterType.Enum(Array(elementsCount, ::getElementName))
            .asValueTool(toolName, description, valueDescription)

        StructureKind.CLASS -> {
            val required = mutableListOf<String>()
            val properties = parameterDescriptors(required, maxDepth, 0)
            ToolDescriptor(
                toolName,
                description,
                requiredParameters = properties.filter { required.contains(it.name) },
                optionalParameters = properties.filterNot { required.contains(it.name) }
            )
        }

        // support FreeForm Object ToolDescriptor
        PolymorphicKind.SEALED,
        StructureKind.OBJECT,
        SerialKind.CONTEXTUAL,
        PolymorphicKind.OPEN,
        StructureKind.MAP,
            -> ToolDescriptor(
            name = toolName,
            description = description,
            requiredParameters = emptyList(),
            optionalParameters = emptyList()
        )
    }
}

internal fun SerialDescriptor.toToolParameterType(maxDepth: Int, depth: Int): ToolParameterType = when (kind) {
    PrimitiveKind.CHAR,
    PrimitiveKind.STRING,
        -> ToolParameterType.String

    PrimitiveKind.BOOLEAN -> ToolParameterType.Boolean
    PrimitiveKind.BYTE,
    PrimitiveKind.SHORT,
    PrimitiveKind.INT,
    PrimitiveKind.LONG,
        -> ToolParameterType.Integer

    PrimitiveKind.FLOAT,
    PrimitiveKind.DOUBLE,
        -> ToolParameterType.Float

    StructureKind.LIST -> ToolParameterType.List(getElementDescriptor(0).toToolParameterType(maxDepth, depth + 1))

    SerialKind.ENUM -> ToolParameterType.Enum(Array(elementsCount, ::getElementName))

    StructureKind.CLASS -> {
        val required = mutableListOf<String>()
        ToolParameterType.Object(
            parameterDescriptors(required, maxDepth, depth + 1),
            required,
            false
        )
    }

    PolymorphicKind.SEALED,
    StructureKind.OBJECT,
    SerialKind.CONTEXTUAL,
    PolymorphicKind.OPEN,
    StructureKind.MAP,
        -> ToolParameterType.Object(
        emptyList(),
        emptyList(),
        true,
        ToolParameterType.String

    )
}

internal fun SerialDescriptor.parameterDescriptors(
    required: MutableList<String>,
    maxDepth: Int,
    depth: Int,
): List<ToolParameterDescriptor> {
    return if (depth > maxDepth) emptyList()
    else List(elementsCount) { i ->
        val name = getElementName(i)
        val descriptor = getElementDescriptor(i)
        val isOptional = isElementOptional(i) || descriptor.isNullable

        if (!isOptional) {
            required.add(name)
        }

        ToolParameterDescriptor(
            name,
            getElementAnnotations(i).filterIsInstance<LLMDescription>().firstOrNull()?.description ?: name,
            getElementDescriptor(i).toToolParameterType(maxDepth, depth)
        )
    }
}