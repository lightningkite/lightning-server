package com.lightningkite.lightningserver.ai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.ToolResult
import ai.koog.prompt.structure.StructureDefinition
import ai.koog.prompt.structure.StructuredRequestConfig
import com.lightningkite.lightningserver.typed.jsonschema.JavascriptCoreType
import com.lightningkite.lightningserver.typed.jsonschema.JavascriptCoreTypeWithNullability
import com.lightningkite.lightningserver.typed.jsonschema.JsonSchemaType
import com.lightningkite.services.data.Description
import com.lightningkite.services.database.ConditionSerializer
import com.lightningkite.services.database.ModificationSerializer
import com.lightningkite.services.database.WrappingSerializer
import com.lightningkite.services.database.innerElement
import com.lightningkite.services.database.innerElement2
import com.lightningkite.services.database.nullElement
import com.lightningkite.services.database.serializableProperties
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlin.math.max
import kotlin.reflect.KClass

internal fun KSerializer<*>.asToolDescriptor(
    name: String,
    description: String,
    module: SerializersModule,
    maxDepth: Int = 3,
): ToolDescriptor {
    if (descriptor.kind != StructureKind.CLASS)
        return ToolDescriptor(
            name, description, requiredParameters = listOf(
                ToolParameterDescriptor(
                    descriptor.serialName.substringBefore('/').substringAfterLast('.').decapitalize(),
                    descriptor.annotations.filterIsInstance<Description>().firstOrNull()?.text ?: "",
                    toolParameterType(module, maxDepth)
                )
            )
        )
    val props = this.serializableProperties
        ?: return ToolDescriptor(
            name, description, requiredParameters = listOf(
                ToolParameterDescriptor(
                    descriptor.serialName.substringBefore('/').substringAfterLast('.').decapitalize(),
                    descriptor.annotations.filterIsInstance<Description>().firstOrNull()?.text ?: "",
                    toolParameterType(module, maxDepth)
                )
            )
        )
    return ToolDescriptor(
        name,
        description,
        requiredParameters = props.map {
            ToolParameterDescriptor(
                name = it.name,
                description = it.annotations.filterIsInstance<Description>().firstOrNull()?.text ?: "",
                type = it.serializer.toolParameterType(module, maxDepth - 1)
            )
        }
    )
}

internal fun KSerializer<*>.toolParameterType(
    module: SerializersModule,
    maxDepth: Int = 3,
): ToolParameterType {
    this.nullElement()?.let {
        return ToolParameterType.AnyOf(
            arrayOf(
                ToolParameterDescriptor("null", "", ToolParameterType.Null),
                ToolParameterDescriptor("present", "", it.toolParameterType(module, maxDepth)),
            )
        )
    }
    if (this is WrappingSerializer<*, *>) return this.getDeferred().toolParameterType(module, maxDepth)
    return when (descriptor.kind) {
        PrimitiveKind.STRING,
        PrimitiveKind.CHAR -> ToolParameterType.String

        PrimitiveKind.BOOLEAN -> ToolParameterType.Boolean
        PrimitiveKind.BYTE,
        PrimitiveKind.SHORT,
        PrimitiveKind.INT,
        PrimitiveKind.LONG -> ToolParameterType.Integer

        PrimitiveKind.FLOAT,
        PrimitiveKind.DOUBLE -> ToolParameterType.Float

        SerialKind.CONTEXTUAL -> {
            (module.getContextual<Any>(this.descriptor.capturedKClass as KClass<Any>) as KSerializer<*>).toolParameterType(
                module,
                maxDepth
            )
        }

        SerialKind.ENUM -> ToolParameterType.Enum(descriptor.elementNames.toList().toTypedArray())
        StructureKind.CLASS -> run {
            if (maxDepth <= 0) return@run ToolParameterType.Object(
                listOf(),
                listOf(),
                true,
                additionalPropertiesType = ToolParameterType.String
            )

            if (descriptor.serialName == "com.lightningkite.services.database.Condition") {
                val subtype = (this as ConditionSerializer<*>).inner
                return@run ToolParameterType.AnyOf(
                    options.map {
                        ToolParameterDescriptor(
                            name = it.serializer.descriptor.serialName,
                            description = "",
                            type = ToolParameterType.Object(
                                properties = listOf(
                                    ToolParameterDescriptor(
                                        name = it.serializer.descriptor.serialName,
                                        description = "",
                                        type = it.serializer.toolParameterType(module, maxDepth - 1)
                                    )
                                )
                            )
                        )
                    }.toTypedArray()
                )
            }
            if (descriptor.serialName == "com.lightningkite.services.database.Modification") {
                val subtype = (this as ModificationSerializer<*>).inner
                return@run ToolParameterType.AnyOf(
                    options.map {
                        ToolParameterDescriptor(
                            name = it.serializer.descriptor.serialName,
                            description = "",
                            type = ToolParameterType.Object(
                                properties = listOf(
                                    ToolParameterDescriptor(
                                        name = it.serializer.descriptor.serialName,
                                        description = "",
                                        type = it.serializer.toolParameterType(module, maxDepth - 1)
                                    )
                                )
                            )
                        )
                    }.toTypedArray()
                )
            }
            val props = this.serializableProperties
                ?: throw IllegalStateException("Serializable properties not found for ${this.descriptor.serialName}")
            ToolParameterType.Object(
                properties = props.map {
                    ToolParameterDescriptor(
                        name = it.name,
                        description = it.annotations.filterIsInstance<Description>().firstOrNull()?.text ?: "",
                        type = it.serializer.toolParameterType(module, maxDepth - 1)
                    )
                }
            )
        }

        StructureKind.LIST -> ToolParameterType.List(innerElement().toolParameterType(module, maxDepth - 1))
        StructureKind.MAP -> ToolParameterType.Object(
            properties = listOf(),
            requiredProperties = emptyList(),
            additionalProperties = true,
            additionalPropertiesType = innerElement2().toolParameterType(module, maxDepth - 1)
        )

        StructureKind.OBJECT -> ToolParameterType.Object(listOf(), listOf(), false, null)
        else -> TODO()
    }
}

public abstract class LsSimpleTool<TArgs>(module: SerializersModule) : SimpleTool<TArgs>() {
    override val descriptor: ToolDescriptor by lazy {
        argsSerializer.asToolDescriptor(name, description, module, 4)
    }
}
