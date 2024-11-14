package com.lightningkite.lightningserver.serialization

import com.lightningkite.lightningserver.engine.engine
import io.ktor.util.*
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder


class AnonType {
    var serializedString: String? = null
    var serializedBytes: ByteArray? = null
    var serializer: KSerializer<*>? = null
    var direct: Any? = null
    var hasDirect: Boolean = false

    constructor(direct: Any?, serializer: KSerializer<*>) {
        this.direct = direct
        hasDirect = true
        this.serializer = serializer
    }
    constructor(serialized: String) {
        this.serializedString = serialized
    }
    constructor(serialized: ByteArray) {
        this.serializedBytes = serialized
    }

    fun serializedString(): String {
        return serializedString ?: run {
            val newSer = engine.internalCommunicationEncoding.encodeString(serializer as KSerializer<Any?>, direct)
            serializedString = newSer
            newSer
        }
    }
    fun serializedBytes(): ByteArray {
        return serializedBytes ?: run {
            val newSer = engine.internalCommunicationEncoding.encodeBytes(serializer as KSerializer<Any?>, direct)
            serializedBytes = newSer
            newSer
        }
    }
    fun <T> value(serializer: KSerializer<T>): T {
        if(hasDirect) return direct as T
        val d = serializedBytes?.let { engine.internalCommunicationEncoding.decodeBytes(serializer, it) } ?:
            engine.internalCommunicationEncoding.decodeString(serializer, serializedString!!)
        direct = d
        hasDirect = true
        return d
    }
    val retriever get() = TypeRetriever { value(it) }
    override fun equals(other: Any?): Boolean = other is AnonType && (
            this.hasDirect && other.hasDirect && this.direct == other.direct ||
            this.serializedString == other.serializedString ||
            this.serializedBytes.contentEquals(other.serializedBytes)
    )

    override fun hashCode(): Int = if(hasDirect) direct.hashCode() else serializedBytes?.contentHashCode() ?: serializedString?.hashCode() ?: 0

    override fun toString(): String = "AnonType(${direct ?: serializedString ?: serializedBytes?.encodeBase64()})"
}

object ByteArrayAnonTypeSerializer: KSerializer<AnonType> {
    override val descriptor: SerialDescriptor = SerialDescriptor("com.lightningkite.lightningserver.serialization.AnonType", ByteArraySerializer().descriptor)
    override fun deserialize(decoder: Decoder): AnonType =
        AnonType(decoder.decodeSerializableValue(ByteArraySerializer()))
    override fun serialize(encoder: Encoder, value: AnonType) =
        encoder.encodeSerializableValue(ByteArraySerializer(), value.serializedBytes())
}
object StringAnonTypeSerializer: KSerializer<AnonType> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.lightningkite.lightningserver.serialization.AnonType", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): AnonType =
        AnonType(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: AnonType) =
        encoder.encodeString(value.serializedString())
}

@JvmInline
value class TypeRetriever(val retriever: (KSerializer<*>) -> Any?) {
    @Suppress("UNCHECKED_CAST")
    operator fun <T> invoke(serializer: KSerializer<T>): T = retriever(serializer) as T
    companion object {
        fun of(retriever: (KSerializer<Nothing>) -> Nothing): TypeRetriever {
            @Suppress("UNCHECKED_CAST")
            return TypeRetriever(retriever as (KSerializer<*>) -> Any?)
        }
        fun literal(value: Any?) = TypeRetriever { value }
    }
}