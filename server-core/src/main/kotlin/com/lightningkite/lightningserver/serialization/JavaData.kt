package com.lightningkite.lightningserver.serialization

import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.modules.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream

class JavaData(override val serializersModule: SerializersModule): BinaryFormat {
    override fun <T> decodeFromByteArray(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T {
        println("Decoding $deserializer from ${bytes.contentToString()}")
        return DataInputDecoder(DataInputStream(ByteArrayInputStream(bytes))).decodeSerializableValue(deserializer)
    }

    override fun <T> encodeToByteArray(serializer: SerializationStrategy<T>, value: T): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputEncoder(DataOutputStream(out)).encodeSerializableValue(serializer, value)
        return out.toByteArray()
    }

    private inner class DataOutputEncoder(val output: DataOutput) : AbstractEncoder() {
        override val serializersModule: SerializersModule get() = this@JavaData.serializersModule
        override fun encodeBoolean(value: Boolean) = output.writeByte(if (value) 1 else 0)
        override fun encodeByte(value: Byte) = output.writeByte(value.toInt())
        override fun encodeShort(value: Short) = output.writeShort(value.toInt())
        override fun encodeInt(value: Int) = output.writeInt(value)
        override fun encodeLong(value: Long) = output.writeLong(value)
        override fun encodeFloat(value: Float) = output.writeFloat(value)
        override fun encodeDouble(value: Double) = output.writeDouble(value)
        override fun encodeChar(value: Char) = output.writeChar(value.code)
        override fun encodeString(value: String) {
            output.writeShort(value.length)
            output.write(value.toByteArray(Charsets.UTF_8))
        }
        override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) = output.writeInt(index)

        override fun beginCollection(descriptor: SerialDescriptor, collectionSize: Int): CompositeEncoder {
            encodeInt(collectionSize)
            return this
        }

        override fun encodeNull() = encodeBoolean(false)
        override fun encodeNotNullMark() = encodeBoolean(true)
    }
    private inner class DataInputDecoder(val input: DataInput, var elementsCount: Int = 0) : AbstractDecoder() {
        private var elementIndex = 0
        override val serializersModule: SerializersModule get() = this@JavaData.serializersModule
        override fun decodeBoolean(): Boolean = input.readByte().toInt() != 0
        override fun decodeByte(): Byte = input.readByte()
        override fun decodeShort(): Short = input.readShort()
        override fun decodeInt(): Int = input.readInt()
        override fun decodeLong(): Long = input.readLong()
        override fun decodeFloat(): Float = input.readFloat()
        override fun decodeDouble(): Double = input.readDouble()
        override fun decodeChar(): Char = input.readChar()
        override fun decodeString(): String {
            val size = input.readUnsignedShort()
            val bytes = ByteArray(size)
            for(i in 0 until size)
                bytes[i] = input.readByte()
            return bytes.toString(Charsets.UTF_8)
        }
        override fun decodeEnum(enumDescriptor: SerialDescriptor): Int = input.readInt()

        override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
            if (elementIndex == elementsCount) return CompositeDecoder.DECODE_DONE
            return elementIndex++
        }

        override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder =
            DataInputDecoder(input, descriptor.elementsCount)

        override fun decodeSequentially(): Boolean = true

        override fun decodeCollectionSize(descriptor: SerialDescriptor): Int =
            decodeInt().also { elementsCount = it }

        override fun decodeNotNullMark(): Boolean = decodeBoolean()
    }
}
