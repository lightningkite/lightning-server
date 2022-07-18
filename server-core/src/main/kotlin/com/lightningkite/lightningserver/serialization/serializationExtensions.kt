package com.lightningkite.lightningserver.serialization

import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.serializerOrNull
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf


@Suppress("UNCHECKED_CAST")
inline fun <reified T> serializerOrContextual(): KSerializer<T> = serializerOrContextual(typeOf<T>())
@Suppress("UNCHECKED_CAST")
fun <T> serializerOrContextual(type: KType): KSerializer<T> {
    serializerOrNull(type)?.let { return it as KSerializer<T> }
    val klass = type.classifier as? KClass<Any> ?: throw IllegalStateException()
    if(type.isMarkedNullable)
        return ContextualSerializer<Any>(klass).nullable as KSerializer<T>
    else
        return (ContextualSerializer<Any>(klass) as KSerializer<T>)
}