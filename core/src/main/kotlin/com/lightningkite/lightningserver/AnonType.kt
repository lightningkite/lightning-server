package com.lightningkite.lightningserver

import com.lightningkite.services.data.KotlinBytesFormat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule

@Serializable(with = AnonTypeSerializer::class)
public class AnonType {
    private var kotlinBytesFormat: KotlinBytesFormat? = null
    private var serializedBytes: ByteArray? = null
    private var serializer: KSerializer<*>? = null
    private var direct: Any? = null
    private var hasDirect: Boolean = false

    public constructor(kotlinBytesFormat: KotlinBytesFormat, direct: Any?, serializer: KSerializer<*>) {
        this.kotlinBytesFormat = kotlinBytesFormat
        this.direct = direct
        hasDirect = true
        this.serializer = serializer
    }

    public constructor(serialized: ByteArray) {
        this.serializedBytes = serialized
    }

    @Suppress("UNCHECKED_CAST")
    public fun serializedBytes(): ByteArray {
        return serializedBytes ?: run {
            val newSer = kotlinBytesFormat!!.encodeToByteArray(serializer as KSerializer<Any?>, direct)
            serializedBytes = newSer
            newSer
        }
    }

    @Suppress("UNCHECKED_CAST")
    public fun <T> value(kotlinBytesFormat: KotlinBytesFormat, serializer: KSerializer<T>): T {
        if (hasDirect) return direct as T
        this.kotlinBytesFormat = kotlinBytesFormat
        val d = serializedBytes!!.let { kotlinBytesFormat.decodeFromByteArray(serializer, it) }
        direct = d
        hasDirect = true
        return d
    }

    override fun equals(other: Any?): Boolean = other is AnonType && (
            this.hasDirect && other.hasDirect && this.direct == other.direct ||
                    this.serializedBytes.contentEquals(other.serializedBytes)
            )

    override fun hashCode(): Int =
        if (hasDirect) direct.hashCode() else serializedBytes?.contentHashCode() ?: 0

    override fun toString(): String = "AnonType(${direct ?: serializedBytes?.toHexString()})"
}

internal object AnonTypeSerializer : KSerializer<AnonType> {
    override val descriptor: SerialDescriptor =
        SerialDescriptor("com.lightningkite.lightningserver.AnonType", ByteArraySerializer().descriptor)

    override fun deserialize(decoder: Decoder): AnonType =
        AnonType(decoder.decodeSerializableValue(ByteArraySerializer()))

    override fun serialize(encoder: Encoder, value: AnonType) =
        encoder.encodeSerializableValue(ByteArraySerializer(), value.serializedBytes())
}