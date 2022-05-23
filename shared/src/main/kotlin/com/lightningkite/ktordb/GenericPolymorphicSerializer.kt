@file:OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)

package com.lightningkite.ktordb

import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlin.IllegalStateException
import kotlin.reflect.KClass

public class MySealedClassSerializer<T : Any>(
    serialName: String,
    val baseClass: KClass<T>,
    val serializerMap: Map<String, KSerializer<out T>>,
    val getName: (T)->String
): KSerializer<T> {
    val indexToName = serializerMap.keys.toTypedArray()
    val nameToIndex = indexToName.withIndex().associate { it.value to it.index }
    val serializers = indexToName.map { serializerMap[it]!! }.toTypedArray()
    fun getIndex(item: T): Int = nameToIndex[getName(item)] ?: throw IllegalStateException("No serializer inside ${descriptor.serialName} found for ${getName(item)}; available: ${indexToName.joinToString()}")

    override val descriptor: SerialDescriptor by lazy(LazyThreadSafetyMode.PUBLICATION) { buildClassSerialDescriptor(serialName) {
        for(s in serializers) {
            element(s.descriptor.serialName, s.descriptor, isOptional = true)
        }
    }}

    override fun deserialize(decoder: Decoder): T {
        return decoder.decodeStructure(descriptor) {
            val index = decodeElementIndex(descriptor)
            if(index == CompositeDecoder.DECODE_DONE) throw IllegalStateException()
            if(index == CompositeDecoder.UNKNOWN_NAME) throw IllegalStateException()
            val result = decodeSerializableElement(descriptor, index, serializers[index])
            assert(decodeElementIndex(descriptor) == CompositeDecoder.DECODE_DONE)
            result
        }
    }

    override fun serialize(encoder: Encoder, value: T) {
        encoder.encodeStructure(descriptor) {
            val index = getIndex(value)
            @Suppress("UNCHECKED_CAST")
            this.encodeSerializableElement<Any?>(descriptor, index, serializers[index] as KSerializer<Any?>, value)
        }
    }
}


public class AltSealedClassSerializer<T : Any>(
    serialName: String,
    override val baseClass: KClass<T>,
    val subclassSerializers: Map<String, KSerializer<out T>>,
    val getName: (T)->String
) : AbstractPolymorphicSerializer<T>() {
    private var _annotations: List<Annotation> = emptyList()

    override val descriptor: SerialDescriptor by lazy(LazyThreadSafetyMode.PUBLICATION) {
        buildSerialDescriptor(serialName, PolymorphicKind.SEALED) {
            element("type", String.serializer().descriptor)
            val elementDescriptor =
                buildSerialDescriptor("kotlinx.serialization.Sealed<${baseClass.simpleName}>", SerialKind.CONTEXTUAL) {
                    subclassSerializers.entries.forEach {
                        element(it.key, it.value.descriptor)
                    }
                }
            element("value", elementDescriptor)
            annotations = _annotations
        }
    }

    override fun findPolymorphicSerializerOrNull(decoder: CompositeDecoder, klassName: String?): DeserializationStrategy<out T>? {
        return subclassSerializers[klassName] ?: throw IllegalStateException("Subclass ${klassName} for ${this.descriptor.serialName} not found in ${subclassSerializers.keys.joinToString()}")
    }

    override fun findPolymorphicSerializerOrNull(encoder: Encoder, value: T): SerializationStrategy<T>? {
        @Suppress("UNCHECKED_CAST")
        return subclassSerializers[getName(value)] as? SerializationStrategy<T> ?: throw IllegalStateException("Subclass ${getName(value)} for ${this.descriptor.serialName} not found in ${subclassSerializers.keys.joinToString()}")
    }
}

public abstract class AbstractPolymorphicSerializer<T : Any> internal constructor() : KSerializer<T> {

    /**
     * Base class for all classes that this polymorphic serializer can serialize or deserialize.
     */
    public abstract val baseClass: KClass<T>

    public final override fun serialize(encoder: Encoder, value: T) {
        val actualSerializer = findPolymorphicSerializer(encoder, value)
        encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, actualSerializer.descriptor.serialName)
            encodeSerializableElement(descriptor, 1, actualSerializer.cast(), value)
        }
    }

    public final override fun deserialize(decoder: Decoder): T = decoder.decodeStructure(descriptor) {
        var klassName: String? = null
        var value: Any? = null
        if (decodeSequentially()) {
            return@decodeStructure decodeSequentially(this)
        }

        mainLoop@ while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> {
                    break@mainLoop
                }
                0 -> {
                    klassName = decodeStringElement(descriptor, index)
                }
                1 -> {
                    klassName = requireNotNull(klassName) { "Cannot read polymorphic value before its type token" }
                    val serializer = findPolymorphicSerializer(this, klassName)
                    value = decodeSerializableElement(descriptor, index, serializer)
                }
                else -> throw SerializationException(
                    "Invalid index in polymorphic deserialization of " +
                            (klassName ?: "unknown class") +
                            "\n Expected 0, 1 or DECODE_DONE(-1), but found $index"
                )
            }
        }
        @Suppress("UNCHECKED_CAST")
        requireNotNull(value) { "Polymorphic value has not been read for class $klassName" } as T
    }

    private fun decodeSequentially(compositeDecoder: CompositeDecoder): T {
        val klassName = compositeDecoder.decodeStringElement(descriptor, 0)
        val serializer = findPolymorphicSerializer(compositeDecoder, klassName)
        return compositeDecoder.decodeSerializableElement(descriptor, 1, serializer)
    }

    /**
     * Lookups an actual serializer for given [klassName] withing the current [base class][baseClass].
     * May use context from the [decoder].
     */
    @InternalSerializationApi
    public open fun findPolymorphicSerializerOrNull(
        decoder: CompositeDecoder,
        klassName: String?
    ): DeserializationStrategy<out T>? = decoder.serializersModule.getPolymorphic(baseClass, klassName)


    /**
     * Lookups an actual serializer for given [value] within the current [base class][baseClass].
     * May use context from the [encoder].
     */
    @InternalSerializationApi
    public open fun  findPolymorphicSerializerOrNull(
        encoder: Encoder,
        value: T
    ): SerializationStrategy<T>? =
        encoder.serializersModule.getPolymorphic(baseClass, value)
}

@InternalSerializationApi
public fun <T : Any> AbstractPolymorphicSerializer<T>.findPolymorphicSerializer(
    decoder: CompositeDecoder,
    klassName: String?
): DeserializationStrategy<out T> =
    findPolymorphicSerializerOrNull(decoder, klassName) ?: throwSubtypeNotRegistered(klassName, baseClass)

@InternalSerializationApi
public fun <T : Any> AbstractPolymorphicSerializer<T>.findPolymorphicSerializer(
    encoder: Encoder,
    value: T
): SerializationStrategy<T> =
    findPolymorphicSerializerOrNull(encoder, value) ?: throwSubtypeNotRegistered(value::class, baseClass)


@JvmName("throwSubtypeNotRegistered")
internal fun throwSubtypeNotRegistered(subClassName: String?, baseClass: KClass<*>): Nothing {
    val scope = "in the scope of '${baseClass.simpleName}'"
    throw SerializationException(
        if (subClassName == null)
            "Class discriminator was missing and no default polymorphic serializers were registered $scope"
        else
            "Class '$subClassName' is not registered for polymorphic serialization $scope.\n" +
                    "Mark the base class as 'sealed' or register the serializer explicitly."
    )
}

@JvmName("throwSubtypeNotRegistered")
internal fun throwSubtypeNotRegistered(subClass: KClass<*>, baseClass: KClass<*>): Nothing =
    throwSubtypeNotRegistered(subClass.simpleName ?: "$subClass", baseClass)

@Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
@PublishedApi
internal inline fun <T> SerializationStrategy<*>.cast(): SerializationStrategy<T> = this as SerializationStrategy<T>