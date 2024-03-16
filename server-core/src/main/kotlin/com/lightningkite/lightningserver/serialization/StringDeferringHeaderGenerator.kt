package com.lightningkite.lightningserver.serialization//package com.lightningkite.lightningserver.serialization
//
//
//import kotlinx.serialization.*
//import kotlinx.serialization.descriptors.SerialDescriptor
//import kotlinx.serialization.descriptors.StructureKind
//import kotlinx.serialization.encoding.CompositeDecoder
//import kotlinx.serialization.encoding.Decoder
//import kotlinx.serialization.internal.AbstractPolymorphicSerializer
//import kotlinx.serialization.modules.SerializersModule
//
//
//@OptIn(InternalSerializationApi::class)
//public class StringDeferringHeaderGenerator(
//    val config: StringDeferringConfig,
//    val descriptor: SerialDescriptor,
//    val outHeaders: MutableList<String>,
//) : Decoder, CompositeDecoder {
//    override val serializersModule: SerializersModule
//        get() = config.serializersModule
//
//    private var currentIndex = 0
//    private val isCollection = descriptor.kind == StructureKind.LIST || descriptor.kind == StructureKind.MAP
//    private val size = if (isCollection) Int.MAX_VALUE else descriptor.elementsCount
//
//    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
//        return StringDeferringHeaderGenerator(config, descriptor, outHeaders).also { copyTagsTo(it) }
//    }
//
//    override fun endStructure(descriptor: SerialDescriptor) {
//        // Nothing
//    }
//
//    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
//        if (deserializer is AbstractPolymorphicSerializer<*>) {
//            val type = map[nested("type")]
//            val actualSerializer: DeserializationStrategy<Any> = deserializer.findPolymorphicSerializer(this, type)
//
//            @Suppress("UNCHECKED_CAST")
//            return actualSerializer.deserialize(this) as T
//        }
//
//        return deserializer.deserialize(this)
//    }
//
//    fun decodeTaggedEnum(tag: String, enumDescriptor: SerialDescriptor): Int {
//        val taggedValue = map.getValue(tag)
//        return enumDescriptor.getElementIndex(taggedValue)
//            .also { if (it == CompositeDecoder.UNKNOWN_NAME) throw SerializationException("Enum '${enumDescriptor.serialName}' does not contain element with name '$taggedValue'") }
//    }
//
//    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
//        while (currentIndex < size) {
//            val name = descriptor.getTag(currentIndex++)
//            if (map.keys.any {
//                    it.startsWith(name) && (it.length == name.length || it[name.length] == '.')
//                }) return currentIndex - 1
//            if (isCollection) {
//                // if map does not contain key we look for, then indices in collection have ended
//                break
//            }
//        }
//        return CompositeDecoder.DECODE_DONE
//    }
//
//    fun decodeTaggedBoolean(tag: String): Boolean = map.getValue(tag).toBoolean()
//    fun decodeTaggedByte(tag: String): Byte = map.getValue(tag).toByte()
//    fun decodeTaggedShort(tag: String): Short = map.getValue(tag).toShort()
//    fun decodeTaggedInt(tag: String): Int = map.getValue(tag).toInt()
//    fun decodeTaggedLong(tag: String): Long = map.getValue(tag).toLong()
//    fun decodeTaggedFloat(tag: String): Float = map.getValue(tag).toFloat()
//    fun decodeTaggedDouble(tag: String): Double = map.getValue(tag).toDouble()
//    fun decodeTaggedChar(tag: String): Char = map.getValue(tag).single()
//    fun decodeTaggedString(tag: String): String = map.getValue(tag)
//
//    // ---- API ----
//
//    fun decodeTaggedNotNullMark(tag: String): Boolean {
//        return map[tag] != config.nullMarker || map.keys.any { it.startsWith(tag + ".") }
//    }
//    fun decodeTaggedNull(tag: String): Nothing? = null
//
//    fun decodeTaggedInline(tag: String, inlineDescriptor: SerialDescriptor): Decoder = this.apply { pushTag(tag) }
//
//    fun <T : Any?> decodeSerializableValue(deserializer: DeserializationStrategy<T>, previousValue: T?): T =
//        decodeSerializableValue(deserializer)
//
//    // ---- Implementation of low-level API ----
//
//    override fun decodeInline(descriptor: SerialDescriptor): Decoder =
//        decodeTaggedInline(popTag(), descriptor)
//
//    override fun decodeNotNullMark(): Boolean {
//        // String might be null for top-level deserialization
//        val currentTag = currentTagOrNull ?: return false
//        return decodeTaggedNotNullMark(currentTag)
//    }
//
//    override fun decodeNull(): Nothing? = null
//
//    override fun decodeBoolean(): Boolean = decodeTaggedBoolean(popTag())
//    override fun decodeByte(): Byte = decodeTaggedByte(popTag())
//    override fun decodeShort(): Short = decodeTaggedShort(popTag())
//    override fun decodeInt(): Int = decodeTaggedInt(popTag())
//    override fun decodeLong(): Long = decodeTaggedLong(popTag())
//    override fun decodeFloat(): Float = decodeTaggedFloat(popTag())
//    override fun decodeDouble(): Double = decodeTaggedDouble(popTag())
//    override fun decodeChar(): Char = decodeTaggedChar(popTag())
//    override fun decodeString(): String = decodeTaggedString(popTag())
//
//    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int = decodeTaggedEnum(popTag(), enumDescriptor)
//
//    override fun decodeBooleanElement(descriptor: SerialDescriptor, index: Int): Boolean =
//        decodeTaggedBoolean(descriptor.getTag(index))
//
//    override fun decodeByteElement(descriptor: SerialDescriptor, index: Int): Byte =
//        decodeTaggedByte(descriptor.getTag(index))
//
//    override fun decodeShortElement(descriptor: SerialDescriptor, index: Int): Short =
//        decodeTaggedShort(descriptor.getTag(index))
//
//    override fun decodeIntElement(descriptor: SerialDescriptor, index: Int): Int =
//        decodeTaggedInt(descriptor.getTag(index))
//
//    override fun decodeLongElement(descriptor: SerialDescriptor, index: Int): Long =
//        decodeTaggedLong(descriptor.getTag(index))
//
//    override fun decodeFloatElement(descriptor: SerialDescriptor, index: Int): Float =
//        decodeTaggedFloat(descriptor.getTag(index))
//
//    override fun decodeDoubleElement(descriptor: SerialDescriptor, index: Int): Double =
//        decodeTaggedDouble(descriptor.getTag(index))
//
//    override fun decodeCharElement(descriptor: SerialDescriptor, index: Int): Char =
//        decodeTaggedChar(descriptor.getTag(index))
//
//    override fun decodeStringElement(descriptor: SerialDescriptor, index: Int): String =
//        decodeTaggedString(descriptor.getTag(index))
//
//    override fun decodeInlineElement(
//        descriptor: SerialDescriptor,
//        index: Int
//    ): Decoder = decodeTaggedInline(descriptor.getTag(index), descriptor.getElementDescriptor(index))
//
//    override fun <T : Any?> decodeSerializableElement(
//        descriptor: SerialDescriptor,
//        index: Int,
//        deserializer: DeserializationStrategy<T>,
//        previousValue: T?
//    ): T {
//        val sub = descriptor.getTag(index)
//        if(map.containsKey(sub) && map[sub]!!.startsWith(config.deferMarker) && map.keys.none { it.startsWith("$sub.") }) {
//            return config.deferredFormat.decodeFromString(deserializer, map[sub]!!.removePrefix(config.deferMarker))
//        } else {
//            return tagBlock(descriptor.getTag(index)) { decodeSerializableValue(deserializer, previousValue) }
//        }
//    }
//
//    override fun <T : Any> decodeNullableSerializableElement(
//        descriptor: SerialDescriptor,
//        index: Int,
//        deserializer: DeserializationStrategy<T?>,
//        previousValue: T?
//    ): T? {
//        val sub = descriptor.getTag(index)
//        if(map.containsKey(sub) && map[sub]!!.startsWith(config.deferMarker) && map.keys.none { it.startsWith("$sub.") }) {
//            return config.deferredFormat.decodeFromString(deserializer, map[sub]!!.removePrefix(config.deferMarker))
//        } else {
//            return tagBlock(descriptor.getTag(index)) {
//                val isNullabilitySupported = deserializer.descriptor.isNullable
//                if (isNullabilitySupported || decodeNotNullMark()) decodeSerializableValue(
//                    deserializer,
//                    previousValue
//                ) else decodeNull()
//            }
//        }
//    }
//
//    private fun <E> tagBlock(tag: String, block: () -> E): E {
//        pushTag(tag)
//        val r = block()
//        if (!flag) {
//            popTag()
//        }
//        flag = false
//        return r
//    }
//
//    private val tagStack = arrayListOf<String>()
//    private val currentTag: String
//        get() = tagStack.last()
//    private val currentTagOrNull: String?
//        get() = tagStack.lastOrNull()
//
//    private fun pushTag(name: String) {
//        tagStack.add(name)
//    }
//
//    private fun copyTagsTo(other: StringDeferringHeaderGenerator) {
//        other.tagStack.addAll(tagStack)
//    }
//
//    private var flag = false
//
//    private fun popTag(): String {
//        val r = tagStack.removeAt(tagStack.lastIndex)
//        flag = true
//        return r
//    }
//
//    fun SerialDescriptor.getTag(index: Int): String = nested(elementName(this, index))
//
//    private fun nested(nestedName: String): String = composeName(currentTagOrNull ?: "", nestedName)
//    fun elementName(descriptor: SerialDescriptor, index: Int): String = descriptor.getElementName(index)
//    fun composeName(parentName: String, childName: String): String =
//        if (parentName.isEmpty()) childName else "$parentName.$childName"
//}
//
