@file:OptIn(ExperimentalSerializationApi::class)

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
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlin.math.max
import kotlin.reflect.KClass
import com.lightningkite.lightningserver.ai.models.*

internal fun KSerializer<*>.asToolDescriptor(
    name: String,
    description: String,
    module: SerializersModule,
    maxDepth: Int = 3,
): ToolDescriptor {
    if (descriptor.kind != StructureKind.CLASS)
        throw IllegalArgumentException("Can only generate tool descriptors for classes")
    val props = this.serializableProperties
        ?: throw IllegalArgumentException("Can only generate tool descriptors for classes")
    return ToolDescriptor(
        name,
        description,
        requiredParameters = props.map {
            println("Prop ${it.name} has direct annos ${it.annotations}")
            println("Prop ${it.name} has type annos ${it.serializer.descriptor.annotations}")
            ToolParameterDescriptor(
                name = it.name,
                description = listOfNotNull(
                    it.annotations.filterIsInstance<Description>().firstOrNull()?.text,
                    it.serializer.descriptor.annotations.filterIsInstance<Description>().firstOrNull()?.text
                ).joinToString("\n") ,
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
            (
                    this.descriptor.capturedKClass
                        ?: throw IllegalArgumentException("Could not find capturedKClass for ${this.descriptor.serialName}'s serial descriptor")
                    ).let {
                    @Suppress("UNCHECKED_CAST")
                    module.getContextual<Any>(it as KClass<Any>)
                        ?: throw IllegalArgumentException("Could not find contextual implementation for ${this.descriptor.serialName}")
                }
                .toolParameterType(
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

            if (descriptor.isInline) {
                return@run innerElement().toolParameterType(module, maxDepth)
            }

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
            println("Normal struct")
            val props = this.serializableProperties
                ?: throw IllegalStateException("Serializable properties not found for ${this.descriptor.serialName}")
            ToolParameterType.Object(
                properties = props.map {
                    println("Prop ${it.name} has direct annos ${it.annotations}")
                    println("Prop ${it.name} has type annos ${it.serializer.descriptor.annotations}")
                    ToolParameterDescriptor(
                        name = it.name,
                        description = listOfNotNull(
                            it.annotations.filterIsInstance<Description>().firstOrNull()?.text,
                            it.serializer.descriptor.annotations.filterIsInstance<Description>().firstOrNull()?.text
                        ).joinToString("\n") ,
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
