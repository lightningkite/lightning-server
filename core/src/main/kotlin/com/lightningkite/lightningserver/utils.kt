package com.lightningkite.lightningserver

import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf


@Suppress("UNCHECKED_CAST")
public inline fun <reified T> serializerOrContextual(): KSerializer<T> = serializerOrContextual(typeOf<T>()) as KSerializer<T>
public fun serializerOrContextual(type: KType): KSerializer<*> {
    val args = type.arguments.map { serializerOrContextual(it.type!!) }
    val kclass = type.classifier as KClass<*>
    return try {
        EmptySerializersModule().serializer(
            kClass = kclass,
            typeArgumentsSerializers = args,
            isNullable = type.isMarkedNullable
        )
    } catch(e: SerializationException) {
        ContextualSerializer(kclass, null, args.toTypedArray()).let {
            @Suppress("UNCHECKED_CAST")
            if(type.isMarkedNullable) (it as KSerializer<Any>).nullable
            else it
        }
    }
}
