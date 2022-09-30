package com.lightningkite.lightningdb

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.capturedKClass
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.SerializersModuleBuilder
import kotlinx.serialization.modules.contextual
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties

fun KSerializer<*>.listElement(): KSerializer<*>? {
    val inner = this.nullElement() ?: this
    val theoreticalMethod = inner::class.java.methods.find { it.name.contains("getElementSerializer") } ?: return null
    return theoreticalMethod.invoke(inner, inner) as KSerializer<*>
}

fun KSerializer<*>.mapKeyElement(): KSerializer<*>? {
    val inner = this.nullElement() ?: this
    val theoreticalMethod = inner::class.java.methods.find { it.name.contains("getKeySerializer") } ?: return null
    return theoreticalMethod.invoke(inner) as KSerializer<*>
}
fun KSerializer<*>.mapValueElement(): KSerializer<*>? {
    val inner = this.nullElement() ?: this
    val theoreticalMethod = inner::class.java.methods.find { it.name.contains("getValueSerializer") } ?: return null
    return theoreticalMethod.invoke(inner) as KSerializer<*>
}

fun KSerializer<*>.nullElement(): KSerializer<*>? {
    try {
        val type = this::class.java
        if(!type.name.contains("Null")) return null
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

@OptIn(ExperimentalSerializationApi::class)
internal fun defer(serialName: String, kind: SerialKind, deferred: () -> SerialDescriptor): SerialDescriptor = object : SerialDescriptor {

    private val original: SerialDescriptor by lazy(deferred)

    override val serialName: String
        get() = serialName
    override val kind: SerialKind
        get() = kind
    override val elementsCount: Int
        get() = original.elementsCount

    override fun getElementName(index: Int): String = original.getElementName(index)
    override fun getElementIndex(name: String): Int = original.getElementIndex(name)
    override fun getElementAnnotations(index: Int): List<Annotation> = original.getElementAnnotations(index)
    override fun getElementDescriptor(index: Int): SerialDescriptor = original.getElementDescriptor(index)
    override fun isElementOptional(index: Int): Boolean = original.isElementOptional(index)

    @ExperimentalSerializationApi
    override val annotations: List<Annotation>
        get() = original.annotations

    @ExperimentalSerializationApi
    override val isInline: Boolean
        get() = original.isInline

    @ExperimentalSerializationApi
    override val isNullable: Boolean
        get() = original.isNullable
}

internal fun <T> defer(deferred: () -> KSerializer<T>): KSerializer<T> = object : KSerializer<T> {

    private val original: KSerializer<T> by lazy(deferred)

    override fun deserialize(decoder: Decoder): T = original.deserialize(decoder)

    override val descriptor: SerialDescriptor
        get() = original.descriptor

    override fun serialize(encoder: Encoder, value: T) {
        original.serialize(encoder, value)
    }
}