package com.lightningkite.ktordb

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.capturedKClass
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.SerializersModuleBuilder
import kotlinx.serialization.modules.contextual
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties

fun KSerializer<*>.listElement(): KSerializer<*>? {
    val theoreticalMethod = this::class.java.methods.find { it.name.contains("getElementSerializer") } ?: return null
    return theoreticalMethod.invoke(this, this) as KSerializer<*>
}

fun KSerializer<*>.mapValueElement(): KSerializer<*>? {
    val theoreticalMethod = this::class.java.methods.find { it.name.contains("getValueSerializer") } ?: return null
    return theoreticalMethod.invoke(this) as KSerializer<*>
}

fun KSerializer<*>.nullElement(): KSerializer<*>? {
    try {
        val theoreticalMethod = this::class.java.getDeclaredField("serializer")
        try { theoreticalMethod.isAccessible = true } catch(e: Exception) {}
        return theoreticalMethod.get(this) as KSerializer<*>
    } catch(e: Exception) { return null }
}

fun SerialDescriptor.nullElement(): SerialDescriptor? {
    try {
        val theoreticalMethod = this::class.java.getDeclaredField("original")
        try { theoreticalMethod.isAccessible = true } catch(e: Exception) {}
        return theoreticalMethod.get(this) as SerialDescriptor
    } catch(e: Exception) { return null }
}
