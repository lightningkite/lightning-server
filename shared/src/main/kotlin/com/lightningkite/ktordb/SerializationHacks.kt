package com.lightningkite.ktordb

import kotlinx.serialization.KSerializer
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