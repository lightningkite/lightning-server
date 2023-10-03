@file:Suppress("OPT_IN_USAGE", "OPT_IN_OVERRIDE")

package com.lightningkite.lightningdb

import kotlinx.serialization.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.internal.GeneratedSerializer
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlin.reflect.KClass
import kotlin.reflect.typeOf


abstract class WrappingSerializer<OUTER, INNER>(val name: String) : KSerializer<OUTER> {
    abstract fun getDeferred(): KSerializer<INNER>
    abstract fun inner(it: OUTER): INNER
    abstract fun outer(it: INNER): OUTER
    val to by lazy { getDeferred() }
    override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor(name) { to.descriptor }
    override fun deserialize(decoder: Decoder): OUTER = outer(decoder.decodeSerializableValue(to))
    override fun serialize(encoder: Encoder, value: OUTER) =
        encoder.encodeSerializableValue(to, inner(value))
}

@OptIn(ExperimentalSerializationApi::class)
internal fun defer(serialName: String, kind: SerialKind, deferred: () -> SerialDescriptor): SerialDescriptor =
    object : SerialDescriptor {

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

class KSerializerKey(val kSerializer: KSerializer<*>) {
    val storedHashCode = kSerializer.descriptor.contentHashCode()
    override fun hashCode(): Int = storedHashCode

    override fun equals(other: Any?): Boolean =
        other is KSerializerKey && this.storedHashCode == other.storedHashCode && this.kSerializer.descriptor matches other.kSerializer.descriptor

    override fun toString(): String = kSerializer.toString()
}

private inline infix fun Int.hashWith(other: Int): Int = this * 31 + other
private inline infix fun Int.hashWith(other: Any): Int = this * 31 + other.hashCode()
private inline infix fun Any.hashWith(other: Int): Int = this.hashCode() * 31 + other
private inline infix fun Any.hashWith(other: Any): Int = this.hashCode() * 31 + other.hashCode()

fun SerialDescriptor.contentHashCode(): Int {
    return this.isNullable hashWith
            this.kind hashWith
            this.serialName hashWith
            this.elementsCount hashWith
            (0 until this.elementsCount).fold(0) { acc, it ->
                acc hashWith this.getElementName(it) hashWith this.getElementDescriptor(it).contentHashCode()
            }
}

infix fun SerialDescriptor.matches(other: SerialDescriptor): Boolean {
    return this.isNullable == other.isNullable &&
            this.kind == other.kind &&
            this.serialName == other.serialName &&
            this.elementsCount == other.elementsCount &&
            (0 until this.elementsCount).all {
                this.getElementName(it) == other.getElementName(it) &&
                        this.getElementDescriptor(it) matches other.getElementDescriptor(it)
            }
}


private class FoundSerializerSignal(val serializer: KSerializer<*>) : Throwable()
@OptIn(ExperimentalSerializationApi::class)
private object FakerDecoder : AbstractDecoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule()
    override fun decodeBoolean() = false
    override fun decodeByte() = 0.toByte()
    override fun decodeChar() = ' '
    override fun decodeDouble() = 0.0
    override fun decodeFloat() = 0f
    override fun decodeInt() = 0
    override fun decodeLong() = 0L
    override fun decodeShort() = 0.toShort()
    override fun decodeString() = ""
    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = 0
    override fun decodeElementIndex(descriptor: SerialDescriptor): Int = CompositeDecoder.DECODE_DONE
    override fun decodeNotNullMark(): Boolean = true
}

private class CheatingBastardDecoder(var count: Int = 0) : AbstractDecoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule()
    var counter = 0
    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        return counter++.let {
            if (it >= descriptor.elementsCount) CompositeDecoder.DECODE_DONE else it
        }
    }

    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
        if (count == 0) throw FoundSerializerSignal(deserializer as KSerializer<*>)
        count--
        return FakerDecoder.decodeSerializableValue(deserializer)
    }
}

fun KSerializer<*>.innerElement(): KSerializer<*> = try {
    if (this is WrappingSerializer<*, *>) this.to.innerElement() else {
        this.deserialize(CheatingBastardDecoder(0))
        throw Exception()
    }
} catch (e: FoundSerializerSignal) {
    e.serializer
}

fun KSerializer<*>.innerElement2(): KSerializer<*> = try {
    if (this is WrappingSerializer<*, *>) this.to.innerElement2() else {
        this.deserialize(CheatingBastardDecoder(1))
        throw Exception()
    }
} catch (e: FoundSerializerSignal) {
    e.serializer
}
fun KSerializer<*>.listElement(): KSerializer<*>? = this.innerElement()
fun KSerializer<*>.mapKeyElement(): KSerializer<*>? = this.innerElement()
fun KSerializer<*>.mapValueElement(): KSerializer<*>? = this.innerElement2()
@OptIn(ExperimentalSerializationApi::class)
fun KSerializer<*>.nullElement(): KSerializer<*>? {
    return if (this.descriptor.isNullable) this.innerElement()
    else null
}

@OptIn(InternalSerializationApi::class)
fun KSerializer<*>.tryTypeParameterSerializers(): Array<KSerializer<*>>? = (this as? GeneratedSerializer<*>)?.typeParametersSerializers()

@OptIn(InternalSerializationApi::class)
fun KSerializer<*>.tryChildSerializers(): Array<KSerializer<*>>? = (this as? GeneratedSerializer<*>)?.childSerializers()

@Suppress("UNCHECKED_CAST")
inline fun <reified T> serializerOrContextual(): KSerializer<T> {
    val t = typeOf<T>()
    return (serializerOrNull(t) ?: ContextualSerializer(t.classifier as KClass<*>)) as KSerializer<T>
}