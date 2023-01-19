@file:Suppress("OPT_IN_USAGE")

package com.lightningkite.lightningdb

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.internal.GeneratedSerializer
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import kotlin.reflect.full.memberProperties
import kotlin.reflect.KProperty1

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
