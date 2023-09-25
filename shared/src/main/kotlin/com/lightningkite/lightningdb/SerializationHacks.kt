@file:OptIn(InternalSerializationApi::class)

package com.lightningkite.lightningdb

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.descriptors.*
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


fun <K, V> KSerializer<K>.fieldSerializer(property: KProperty1<K, V>): KSerializer<V>? {
    val index = this.descriptor.elementNames.indexOf(property.name)
    @Suppress("UNCHECKED_CAST")
    return this.childSerializers()?.get(index) as? KSerializer<V>
}

fun <K> KSerializer<K>.fieldSerializer(fieldName: String): KSerializer<*>? {
    val index = this.descriptor.elementNames.indexOf(fieldName)
    return this.childSerializers()?.get(index)
}
fun SerialDescriptor.nullElement(): SerialDescriptor? {
    try {
        val theoreticalMethod = this::class.java.getDeclaredField("original")
        try { theoreticalMethod.isAccessible = true } catch(e: Exception) {}
        return theoreticalMethod.get(this) as SerialDescriptor
    } catch(e: Exception) { return null }
}