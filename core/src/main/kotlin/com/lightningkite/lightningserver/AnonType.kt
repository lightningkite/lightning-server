package com.lightningkite.lightningserver

import com.lightningkite.services.data.KotlinBytesFormat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule

/**
 * A type-erased serializable container that can hold any value with its serializer.
 *
 * This class allows serializing and deserializing values of unknown types at compile time.
 * It stores either the direct value with its serializer, or the serialized byte representation.
 * Useful for scenarios where the actual type isn't known until runtime.
 *
 * The value is lazily serialized/deserialized as needed to optimize performance.
 */
@Serializable(with = AnonTypeSerializer::class)
public class AnonType {
    private var kotlinBytesFormat: KotlinBytesFormat? = null
    private var serializedBytes: ByteArray? = null
    private var serializer: KSerializer<*>? = null
    private var direct: Any? = null
    private var hasDirect: Boolean = false

    /**
     * Creates an AnonType from a direct value with its serializer.
     *
     * @param kotlinBytesFormat The format to use for serialization when needed
     * @param direct The actual value to store
     * @param serializer The serializer for the value's type
     */
    public constructor(kotlinBytesFormat: KotlinBytesFormat, direct: Any?, serializer: KSerializer<*>) {
        this.kotlinBytesFormat = kotlinBytesFormat
        this.direct = direct
        hasDirect = true
        this.serializer = serializer
    }

    /**
     * Creates an AnonType from already-serialized bytes.
     * The value will be deserialized lazily when accessed.
     *
     * @param serialized The serialized byte representation
     */
    public constructor(serialized: ByteArray) {
        this.serializedBytes = serialized
    }

    /**
     * Gets the serialized byte representation of the contained value.
     * If not already serialized, performs serialization using the stored format and serializer.
     *
     * @return The serialized bytes
     */
    @Suppress("UNCHECKED_CAST")
    public fun serializedBytes(): ByteArray {
        return serializedBytes ?: run {
            val newSer = kotlinBytesFormat!!.encodeToByteArray(serializer as KSerializer<Any?>, direct)
            serializedBytes = newSer
            newSer
        }
    }

    /**
     * Retrieves the contained value, deserializing it if necessary.
     *
     * @param T The expected type of the value
     * @param kotlinBytesFormat The format to use for deserialization
     * @param serializer The serializer for the expected type
     * @return The deserialized value
     */
    @Suppress("UNCHECKED_CAST")
    public fun <T> value(kotlinBytesFormat: KotlinBytesFormat, serializer: KSerializer<T>): T {
        if (hasDirect) return direct as T
        this.kotlinBytesFormat = kotlinBytesFormat
        val d = serializedBytes!!.let { kotlinBytesFormat.decodeFromByteArray(serializer, it) }
        direct = d
        hasDirect = true
        return d
    }

    // TODO: Potential null safety issue - serializedBytes could be null when both instances have hasDirect=false but serializedBytes not yet populated
    override fun equals(other: Any?): Boolean = other is AnonType && (
            this.hasDirect && other.hasDirect && this.direct == other.direct ||
                    this.serializedBytes.contentEquals(other.serializedBytes)
            )

    // TODO: Potential NPE if hasDirect=true but direct=null. Should handle null case: direct?.hashCode() ?: 0
    override fun hashCode(): Int =
        if (hasDirect) direct.hashCode() else serializedBytes?.contentHashCode() ?: 0

    override fun toString(): String = "AnonType(${direct ?: serializedBytes?.toHexString()})"
}

/**
 * Internal serializer for AnonType that serializes/deserializes as a ByteArray.
 */
internal object AnonTypeSerializer : KSerializer<AnonType> {
    override val descriptor: SerialDescriptor =
        SerialDescriptor("com.lightningkite.lightningserver.AnonType", ByteArraySerializer().descriptor)

    override fun deserialize(decoder: Decoder): AnonType =
        AnonType(decoder.decodeSerializableValue(ByteArraySerializer()))

    override fun serialize(encoder: Encoder, value: AnonType) =
        encoder.encodeSerializableValue(ByteArraySerializer(), value.serializedBytes())
}

/*
 * TODO: API Recommendations
 *
 * 1. Fix null safety issues in equals() and hashCode() methods to prevent potential NPEs
 * 2. Consider making the value() method throw a more descriptive exception when deserialization fails
 *    rather than relying on the !! operator
 * 3. Consider adding a convenience factory method like:
 *    inline fun <reified T> AnonType.Companion.of(value: T, format: KotlinBytesFormat)
 * 4. Consider adding a method to check if the value has been deserialized without triggering deserialization
 * 5. Thread safety: Consider whether concurrent access scenarios need to be documented or handled
 */