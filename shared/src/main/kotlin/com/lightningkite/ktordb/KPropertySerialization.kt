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
import java.lang.reflect.ParameterizedType
import kotlin.reflect.full.memberProperties
import kotlin.reflect.KProperty1

@Serializer(KProperty1::class)
class KPropertySerializer<T>(val inner: KSerializer<T>): KSerializer<KProperty1Partial<T>> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("KProperty1Partial<${inner.descriptor.serialName}>", PrimitiveKind.STRING)

    @Suppress("UNCHECKED_CAST")
    val fields = inner.attemptGrabFields()

    override fun deserialize(decoder: Decoder): KProperty1Partial<T> {
        val value = decoder.decodeString()
        val name = value
        return fields[name]!!
    }

    override fun serialize(encoder: Encoder, value: KProperty1Partial<T>) {
        encoder.encodeString(value.name)
    }
}

fun <T> KSerializer<T>.attemptGrabFields(): Map<String, KProperty1<T, *>> = this::class.java.genericSuperclass.let { it as ParameterizedType }.actualTypeArguments.first().let {
    var current = it
    while(true) {
        when(current) {
            is Class<*> -> break
            is ParameterizedType -> current = current.rawType
            else -> TODO(current.toString())
        }
    }
    current as Class<*>
}.kotlin.memberProperties.associate { it.name to (it as KProperty1Partial<T>) }