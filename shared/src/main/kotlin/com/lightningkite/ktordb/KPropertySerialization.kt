@file:Suppress("OPT_IN_USAGE")

package com.lightningkite.ktordb

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.internal.GeneratedSerializer
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import kotlin.reflect.full.memberProperties
import kotlin.reflect.KProperty1

@Serializer(KProperty1::class)
class KPropertyPartialSerializer<T>(val inner: KSerializer<T>) : KSerializer<KProperty1Partial<T>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("KProperty1Partial<${inner.descriptor.serialName}>", PrimitiveKind.STRING)

    @Suppress("UNCHECKED_CAST")
    val fields = inner.attemptGrabFields()

    override fun deserialize(decoder: Decoder): KProperty1Partial<T> {
        val value = decoder.decodeString()
        val name = value
        return fields[name]!!
    }

    override fun serialize(encoder: Encoder, value: KProperty1Partial<T>) {
        encoder.encodeString(value.property.name)
    }
}

@OptIn(InternalSerializationApi::class)
fun <T> KSerializer<T>.attemptGrabFields(): Map<String, KProperty1Partial<T>> = this::class.java.genericInterfaces
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
        it.name to KProperty1Partial(it as KProperty1<T, *>)
    }

private fun Type.clazz(): Class<*> = when (this) {
    is ParameterizedType -> this.rawType.clazz()
    is Class<*> -> this
    else -> TODO()
}
