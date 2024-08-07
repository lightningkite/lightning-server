package com.lightningkite.lightningserver

import kotlinx.serialization.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.SerializersModule

class StringArrayFormat(override val serializersModule: SerializersModule) : StringFormat {

    @OptIn(ExperimentalSerializationApi::class)
    private inner class DataOutputEncoder(val output: (String)->Unit) : AbstractEncoder() {
        override val serializersModule: SerializersModule get() = this@StringArrayFormat.serializersModule
        override fun encodeBoolean(value: Boolean) { output(value.toString()) }
        override fun encodeByte(value: Byte) { output(value.toString()) }
        override fun encodeShort(value: Short) { output(value.toString()) }
        override fun encodeInt(value: Int) { output(value.toString()) }
        override fun encodeLong(value: Long) { output(value.toString()) }
        override fun encodeFloat(value: Float) { output(value.toString()) }
        override fun encodeDouble(value: Double) { output(value.toString()) }
        override fun encodeChar(value: Char) { output(value.toString()) }
        override fun encodeString(value: String) { output(value) }
        override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) { output(enumDescriptor.getElementName(index)) }
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
                    output(descriptor.getElementName(index))
                    lastMarkerWritten = index
                }
            }
            return true
        }

        override fun endStructure(descriptor: SerialDescriptor) {
            if(descriptor.kind == StructureKind.CLASS && lastMarkerWritten != descriptor.elementsCount - 1) {
                if(descriptor.elementsCount >= 0xFE) {
                    output("end")
                }
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private inner class DataInputDecoder(val input: () -> String, var elementsCount: Int = 0, val seq: Boolean = false) : AbstractDecoder() {
        private var elementIndex = 0
        override val serializersModule: SerializersModule get() = this@StringArrayFormat.serializersModule
        override fun decodeBoolean(): Boolean = input().toBoolean()
        override fun decodeByte(): Byte = input().toByte()
        override fun decodeShort(): Short = input().toShort()
        override fun decodeInt(): Int = input().toInt()
        override fun decodeLong(): Long = input().toLong()
        override fun decodeFloat(): Float = input().toFloat()
        override fun decodeDouble(): Double = input().toDouble()
        override fun decodeChar(): Char = input().first()
        override fun decodeString(): String = input()

        override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
            return enumDescriptor.getElementIndex(input())
        }

        override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
            if(descriptor.kind == StructureKind.CLASS) {
                if(elementIndex >= descriptor.elementsCount) {
                    return CompositeDecoder.DECODE_DONE
                }
                if(!descriptor.isElementOptional(elementIndex) && elementIndex < descriptor.elementsCount) {
                    return elementIndex++
                }
                val index = descriptor.getElementIndex(input())
                if (index == 0xFF) {
                    return CompositeDecoder.DECODE_DONE
                }
                if (index >= descriptor.elementsCount) throw SerializationException()
                elementIndex = index + 1
                return index
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

    fun <T> decodeFromStringList(deserializer: DeserializationStrategy<T>, list: List<String>): T {
        var index = 0
        return DataInputDecoder({ list[index++]}).decodeSerializableValue(deserializer)
    }
    fun <T> encodeToStringList(serializer: SerializationStrategy<T>, value: T): List<String> = buildList{
        DataOutputEncoder {add(it)}.encodeSerializableValue(serializer, value)
    }

    override fun <T> decodeFromString(deserializer: DeserializationStrategy<T>, string: String): T {
        val list = string.replace("\\,", "___COMMA___").replace("\\\\", "\\").split(',').map { it.replace("___COMMA___", ",") }
        var index = 0
        return DataInputDecoder({ list[index++]}).decodeSerializableValue(deserializer)
    }

    override fun <T> encodeToString(serializer: SerializationStrategy<T>, value: T): String = buildString {
        var first = true
        DataOutputEncoder {
            if(first) first = false
            else append(',')
            append(it.replace("\\", "\\\\").replace(",", "\\,"))
        }.encodeSerializableValue(serializer, value)
    }
}


