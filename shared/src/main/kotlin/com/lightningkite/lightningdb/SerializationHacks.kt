@file:OptIn(InternalSerializationApi::class)

package com.lightningkite.lightningdb

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.capturedKClass
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.internal.GeneratedSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.SerializersModuleBuilder
import kotlinx.serialization.modules.contextual
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

abstract class WrappingSerializer<OUTER, INNER>(val name: String): KSerializer<OUTER> {
    abstract fun getDeferred(): KSerializer<INNER>
    abstract fun inner(it: OUTER): INNER
    abstract fun outer(it: INNER): OUTER
    val to by lazy { getDeferred() }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor(name) { to.descriptor }
    override fun deserialize(decoder: Decoder): OUTER = outer(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: OUTER) =
        encoder.encodeSerializableValue(to, inner(value))
}

//fun SerializersModule.test() {
//    this.
//}

fun KSerializer<*>.listElement(): KSerializer<*>? {
    if(this is WrappingSerializer<*, *>) return this.to.listElement()
    val inner = this.nullElement() ?: this
    val theoreticalMethod = inner::class.java.methods.find { it.name.contains("getElementSerializer") } ?: return null
    return theoreticalMethod.invoke(inner, inner) as KSerializer<*>
}

fun KSerializer<*>.mapKeyElement(): KSerializer<*>? {
    if(this is WrappingSerializer<*, *>) return this.to.mapKeyElement()
    val inner = this.nullElement() ?: this
    val theoreticalMethod = inner::class.java.methods.find { it.name.contains("getKeySerializer") } ?: return null
    return theoreticalMethod.invoke(inner) as KSerializer<*>
}
fun KSerializer<*>.mapValueElement(): KSerializer<*>? {
    if(this is WrappingSerializer<*, *>) return this.to.mapValueElement()
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

@OptIn(InternalSerializationApi::class)
fun KSerializer<*>.childSerializers() = (this as? GeneratedSerializer<*>)?.childSerializers()

@OptIn(InternalSerializationApi::class)
fun <T> KSerializer<T>.attemptGrabFields(): Map<String, KProperty1<T, *>> = this::class.java.genericInterfaces
    .asSequence()
    .filterIsInstance<ParameterizedType>()
    .filter { it.rawType == GeneratedSerializer::class.java }
    .first()
    .actualTypeArguments
    .first()
    .clazz()
    .kotlin
    .memberProperties
    .associate {
        @Suppress("UNCHECKED_CAST")
        it.name to (it as KProperty1<T, *>)
    }

private fun Type.clazz(): Class<*> = when (this) {
    is ParameterizedType -> this.rawType.clazz()
    is Class<*> -> this
    else -> TODO()
}
