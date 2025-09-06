package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.services.database.listElement
import com.lightningkite.services.database.mapValueElement
import com.lightningkite.services.database.tryTypeParameterSerializers3
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.capturedKClass

@OptIn(ExperimentalSerializationApi::class)
context(_: SdkFormat)
public fun KSerializer<*>.kotlinTypeString(): String {
    return when (this.descriptor.kind) {
        StructureKind.MAP -> "Map<String, ${this.mapValueElement()!!.kotlinTypeString()}>"

        StructureKind.LIST -> "List<${this.listElement()!!.kotlinTypeString()}>"
        SerialKind.CONTEXTUAL -> descriptor.capturedKClass!!.qualifiedName!!
        else -> {
            descriptor.serialName
                .substringBefore('/')
                .substringBefore('<') + (tryTypeParameterSerializers3()?.takeUnless { it.isEmpty() }
                ?.joinToString(", ", "<", ">") { it.kotlinTypeString() } ?: "")
        }
    }
}

context(_: SdkFormat)
public fun KSerializer<*>.isUnit(): Boolean = descriptor.serialName == "kotlin.Unit"