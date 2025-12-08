package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.services.database.listElement
import com.lightningkite.services.database.mapValueElement
import com.lightningkite.services.database.nullElement
import com.lightningkite.services.database.typeParametersSerializersOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.capturedKClass
import kotlinx.serialization.modules.SerializersModule

@OptIn(ExperimentalSerializationApi::class)
public fun KSerializer<*>.kotlinTypeString(): String {
    return when (this.descriptor.kind) {
        StructureKind.MAP -> "Map<String, ${this.mapValueElement()!!.kotlinTypeString()}>"

        StructureKind.LIST -> "List<${this.listElement()!!.kotlinTypeString()}>"
        SerialKind.CONTEXTUAL -> descriptor.capturedKClass?.qualifiedName ?: descriptor.serialName.substringBefore('/')
        else -> {
            descriptor.serialName.substringBefore('/').substringBefore('<') +
                    (typeParametersSerializersOrNull()
                        ?.takeUnless { it.isEmpty() }
                        ?.joinToString(", ", "<", ">") { it.kotlinTypeString() }
                        ?: "")
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
context(server: ServerRuntime)
public fun KSerializer<*>.kotlinSerializer(): String {
    nullElement()?.let { return it.kotlinSerializer() + ".nullable" }

    return when (this.descriptor.kind) {
        StructureKind.MAP -> "MapSerializer(String.serializer(), ${
            this.mapValueElement()!!.kotlinSerializer()
        })"

        StructureKind.LIST -> "ListSerializer(${this.listElement()!!.kotlinSerializer()})"

        SerialKind.CONTEXTUAL -> "ContextualSerializer(${kotlinTypeString()}::class, null, arrayOf(${
            this.typeParametersSerializersOrNull()?.joinToString(", ") { it.kotlinSerializer() } ?: ""
        }))"

        else -> {
            descriptor.serialName
                .substringBefore('/')
                .substringBefore('<')
                .plus(".serializer")
                .plus(typeParametersSerializersOrNull()?.joinToString(", ", "(", ")") { it.kotlinSerializer() } ?: "()")
        }
    }
}

public fun KSerializer<*>.isUnit(): Boolean = descriptor.serialName == "kotlin.Unit"