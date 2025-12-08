package com.lightningkite.lightningserver.serialization

import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Obtains a serializer for the reified type T, falling back to contextual serialization if needed.
 *
 * This function attempts to find a built-in serializer for the type. If that fails
 * (e.g., for types requiring contextual serializers), it returns a ContextualSerializer instead.
 *
 * This is useful for generic code that needs to work with both @Serializable types and
 * types that require contextual serializers from a SerializersModule.
 *
 * @return A KSerializer for type T, either built-in or contextual
 */
@Suppress("UNCHECKED_CAST")
public inline fun <reified T> serializerOrContextual(): KSerializer<T> = serializerOrContextual(typeOf<T>()) as KSerializer<T>

/**
 * Obtains a serializer for the given KType, falling back to contextual serialization if needed.
 *
 * This function:
 * 1. Recursively resolves serializers for type arguments
 * 2. Attempts to find a built-in serializer using EmptySerializersModule
 * 3. Falls back to ContextualSerializer if no built-in serializer exists
 * 4. Wraps the result in a nullable serializer if the type is nullable
 *
 * **Important gotcha:** This uses EmptySerializersModule for the initial lookup, so it will
 * only find built-in serializers. Custom serializers registered in a SerializersModule
 * will not be found and will fall back to ContextualSerializer.
 *
 * @param type The Kotlin type to get a serializer for
 * @return A KSerializer for the type
 * @throws IllegalArgumentException if type arguments contain null types
 */
@OptIn(ExperimentalSerializationApi::class)
public fun serializerOrContextual(type: KType): KSerializer<*> {
    val args = type.arguments.mapIndexed { index, it -> serializerOrContextual(it.type ?: throw IllegalArgumentException("Type argument $index has no 'type' - we can't make a serializer from it as requested.")) }
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
