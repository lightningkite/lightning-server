package com.lightningkite.lightningserver.serialization

import com.lightningkite.lightningserver.bytes.toHexString
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.SerializersModule
import java.io.*

class JavaData(override val serializersModule: SerializersModule) : BinaryFormat {
    override fun <T> decodeFromByteArray(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T {
        return DataInputDecoder(DataInputStream(ByteArrayInputStream(bytes))).decodeSerializableValue(deserializer)
    }

    override fun <T> encodeToByteArray(serializer: SerializationStrategy<T>, value: T): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputEncoder(DataOutputStream(out)).encodeSerializableValue(serializer, value)
        return out.toByteArray()
    }

    private inner class DataOutputEncoder(val output: DataOutput) : AbstractEncoder() {
        override val serializersModule: SerializersModule get() = this@JavaData.serializersModule
        override fun encodeBoolean(value: Boolean) = output.writeByte((if (value) 1 else 0))
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

        override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
            if(enumDescriptor.elementsCount > 0x7F) output.writeShort(index)
            else output.write(index)
        }

        override fun beginCollection(descriptor: SerialDescriptor, collectionSize: Int): CompositeEncoder {
            encodeInt(collectionSize)
            return DataOutputEncoder(output)
        }

        override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
            return DataOutputEncoder(output)
        }

        override fun encodeNull() = encodeBoolean(false)
        override fun encodeNotNullMark() = encodeBoolean(true)

        var lastMarkerWritten = -1
        override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
            if(descriptor.kind == StructureKind.CLASS) {
                if(index == lastMarkerWritten + 1 && !descriptor.isElementOptional(index)) {
                    lastMarkerWritten = index
                } else {
                    if(descriptor.elementsCount >= 0xFE) {
                        output.writeShort(index)
                    } else {
                        output.writeByte(index)
                    }
                    lastMarkerWritten = index
                }
            }
            return true
        }

        override fun endStructure(descriptor: SerialDescriptor) {
            if(descriptor.kind == StructureKind.CLASS && lastMarkerWritten != descriptor.elementsCount - 1) {
                if(descriptor.elementsCount >= 0xFE) {
                    output.writeShort(0xFFFF)
                } else {
                    output.writeByte(0xFF)
                }
            }
        }
    }

    private inner class DataInputDecoder(val input: DataInput, var elementsCount: Int = 0, val seq: Boolean = false) : AbstractDecoder() {
        private var elementIndex = 0
        override val serializersModule: SerializersModule get() = this@JavaData.serializersModule
        override fun decodeBoolean(): Boolean = (input.readByte().toInt() != 0)
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
            for (i in 0 until size)
                bytes[i] = input.readByte()
            return bytes.toString(Charsets.UTF_8)
        }

        override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
            return if(enumDescriptor.elementsCount > 0x7F) input.readUnsignedShort()
            else input.readUnsignedByte()
        }

        override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
            if(descriptor.kind == StructureKind.CLASS) {
                if(elementIndex >= descriptor.elementsCount) {
                    return CompositeDecoder.DECODE_DONE
                }
                if(!descriptor.isElementOptional(elementIndex) && elementIndex < descriptor.elementsCount) {
                    return elementIndex++
                }
                if(descriptor.elementsCount >= 0xFE) {
                    val index = input.readShort().toInt()
                    if (index == 0xFF) {
                        return CompositeDecoder.DECODE_DONE
                    }
                    if (index >= descriptor.elementsCount) throw SerializationException()
                    elementIndex = index + 1
                    return index
                } else {
                    val index = input.readByte().toInt()
                    if (index == 0xFFFF) {
                        return CompositeDecoder.DECODE_DONE
                    }
                    if (index >= descriptor.elementsCount) throw SerializationException()
                    elementIndex = index + 1
                    return index
                }
            } else {
                if (elementIndex == elementsCount) return CompositeDecoder.DECODE_DONE
                return elementIndex++
            }
        }

        override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
            return DataInputDecoder(input, descriptor.elementsCount, descriptor.kind != StructureKind.CLASS)
        }

        override fun decodeSequentially(): Boolean = seq

        override fun decodeCollectionSize(descriptor: SerialDescriptor): Int =
            decodeInt().also { elementsCount = it }

        override fun decodeNotNullMark(): Boolean = decodeBoolean()
    }

    fun <T> encodeToHexStringDebug(serializer: SerializationStrategy<T>, value: T): String = buildString{
        DataOutputEncoder(object: DataOutput {
            override fun write(b: Int) {
                append(b.toString(16) + " b ")
            }

            override fun write(b: ByteArray) {
                append(b.toHexString() + " Bytes ")
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                append(b.sliceArray(off .. off + len).toHexString() + " Bytes ")
            }

            override fun writeBoolean(v: Boolean) {
                append("$v ")
            }

            override fun writeByte(v: Int) {
                append(v.toUByte().toString(16) + " Byte ")
            }

            override fun writeShort(v: Int) {
                append(v.toUShort().toString(16) + " Short ")
            }

            override fun writeChar(v: Int) {
                append(v.toString(16) + " Char ")
            }

            override fun writeInt(v: Int) {
                append(v.toUInt().toString(16) + " Int ")
            }

            override fun writeLong(v: Long) {
                append(v.toULong().toString(16) + " Long ")
            }

            override fun writeFloat(v: Float) {
                append("$v Float ")
            }

            override fun writeDouble(v: Double) {
                append("$v Double ")
            }

            override fun writeBytes(s: String) {
                append("$s Text ")
            }

            override fun writeChars(s: String) {
                append("$s Text ")
            }

            override fun writeUTF(s: String) {
                append("$s Text ")
            }
        }).encodeSerializableValue(serializer, value)
    }
}


