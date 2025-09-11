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
context(_: SDK.Format) // to avoid namespace pollution
public fun KSerializer<*>.kotlinTypeString(): String {
    return when (this.descriptor.kind) {
        StructureKind.MAP -> "Map<String, ${this.mapValueElement()!!.kotlinTypeString()}>"

        StructureKind.LIST -> "List<${this.listElement()!!.kotlinTypeString()}>"
        SerialKind.CONTEXTUAL -> descriptor.capturedKClass!!.qualifiedName!!
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
context(_: SDK.Format, server: ServerRuntime)
public fun KSerializer<*>.kotlinSerializer(): String {
    fun KSerializer<*>.uncontextualize(): KSerializer<*> {
        return if (this.descriptor.kind == SerialKind.CONTEXTUAL) {
            val kClass = descriptor.capturedKClass ?: throw IllegalStateException("No captured KClass found for $descriptor")
            server.internalSerialization.serializersModule.getContextual(kClass)
                ?: server.externalSerialization.serializersModule.getContextual(kClass)
                ?: throw IllegalStateException("No contextual serializer found for ${descriptor.capturedKClass!!.qualifiedName}")
        } else this
    }

    nullElement()?.let { return it.kotlinSerializer() + ".nullable" }

    return when (this.descriptor.kind) {
        StructureKind.MAP -> "MapSerializer(String.serializer(), ${
            this.mapValueElement()!!.kotlinSerializer()
        })"

        StructureKind.LIST -> "ListSerializer(${this.listElement()!!.kotlinSerializer()})"

        SerialKind.CONTEXTUAL -> "ContextualSerializer(${kotlinTypeString()}::class, null, arrayOf(${
            this.uncontextualize().typeParametersSerializersOrNull()?.joinToString(", ") { it.kotlinSerializer() } ?: ""
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