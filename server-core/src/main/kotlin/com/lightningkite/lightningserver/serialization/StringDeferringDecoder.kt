package com.lightningkite.lightningserver.serialization


import kotlinx.serialization.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.internal.AbstractPolymorphicSerializer
import kotlinx.serialization.modules.SerializersModule


@OptIn(InternalSerializationApi::class)
class StringDeferringDecoder(
    val config: StringDeferringConfig,
    val descriptor: SerialDescriptor,
    val map: Map<String, String>,
    val errorContext: ()->String = {""},
) : Decoder, CompositeDecoder {
    override val serializersModule: SerializersModule
        get() = config.serializersModule

    private var currentIndex = 0
    private val isCollection = descriptor.kind == StructureKind.LIST || descriptor.kind == StructureKind.MAP
    private val size = if (isCollection) Int.MAX_VALUE else descriptor.elementsCount

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        return StringDeferringDecoder(config, descriptor, map).also { copyTagsTo(it) }
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        // Nothing
    }

    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
        if (deserializer is AbstractPolymorphicSerializer<*>) {
            val type = map[nested("type")]
            val actualSerializer: DeserializationStrategy<Any> = deserializer.findPolymorphicSerializer(this, type)

            @Suppress("UNCHECKED_CAST")
            return actualSerializer.deserialize(this) as T
        }

        return deserializer.deserialize(this)
    }

    fun decodeTaggedEnum(tag: String, enumDescriptor: SerialDescriptor): Int {
        val taggedValue = map.getValue(tag)
        return enumDescriptor.getElementIndex(taggedValue)
            .also {
                val caseInsensitiveMatch = enumDescriptor.elementNames.indexOfFirst { it.equals(taggedValue, ignoreCase = true) }
                if(caseInsensitiveMatch != -1) return caseInsensitiveMatch
                if (it == CompositeDecoder.UNKNOWN_NAME) throw SerializationException("Enum '${enumDescriptor.serialName}' does not contain element with name '$taggedValue'")
            }
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        while (currentIndex < size) {
            val name = descriptor.getTag(currentIndex++)
            if (map.keys.any {
                    it.startsWith(name) && (it.length == name.length || it[name.length] == '.')
                }) return currentIndex - 1
            if (isCollection) {
                // if map does not contain key we look for, then indices in collection have ended
                break
            }
        }
        return CompositeDecoder.DECODE_DONE
    }

    fun decodeTaggedBoolean(tag: String): Boolean = ewrap(tag, "Boolean") { it.toBoolean() }
    fun decodeTaggedByte(tag: String): Byte = ewrap(tag, "Byte") { it.toByte() }
    fun decodeTaggedShort(tag: String): Short = ewrap(tag, "Short") { it.toShort() }
    fun decodeTaggedInt(tag: String): Int = ewrap(tag, "Int") { it.toInt() }
    fun decodeTaggedLong(tag: String): Long = ewrap(tag, "Long") { it.toLong() }
    fun decodeTaggedFloat(tag: String): Float = ewrap(tag, "Float") { it.toFloat() }
    fun decodeTaggedDouble(tag: String): Double = ewrap(tag, "Double") { it.toDouble() }
    fun decodeTaggedChar(tag: String): Char = ewrap(tag, "Char") { it.single() }
    fun decodeTaggedString(tag: String): String = ewrap(tag, "String") { it }

    inline fun <T> ewrap(tag: String, type: String, action: (String)->T): T {
        return try {
            action(map[tag] ?: throw SerializationException("${errorContext()}Key ${tag} expected a $type, but it wasn't present"))
        } catch(e: Exception) {
            throw SerializationException("${errorContext()}Key ${tag} could not parse '${map[tag]}' as a $type", e)
        }
    }

    // ---- API ----

    fun decodeTaggedNotNullMark(tag: String): Boolean {
        val v = map[tag]
        if(v == config.nullMarker) {
            return false
        }
        if(map.keys.any { it.startsWith("$tag.") }) {
            if (v?.equals("false", ignoreCase = true) == true) return false
        }
        return true
    }

    fun decodeTaggedInline(tag: String): Decoder = this.apply { pushTag(tag) }

    // ---- Implementation of low-level API ----

    override fun decodeInline(descriptor: SerialDescriptor): Decoder =
        decodeTaggedInline(popTag())

    override fun decodeNotNullMark(): Boolean {
        // String might be null for top-level deserialization
        val currentTag = currentTagOrNull ?: return false
        return decodeTaggedNotNullMark(currentTag)
    }

    override fun decodeNull(): Nothing? = null

    override fun decodeBoolean(): Boolean = decodeTaggedBoolean(popTag())
    override fun decodeByte(): Byte = decodeTaggedByte(popTag())
    override fun decodeShort(): Short = decodeTaggedShort(popTag())
    override fun decodeInt(): Int = decodeTaggedInt(popTag())
    override fun decodeLong(): Long = decodeTaggedLong(popTag())
    override fun decodeFloat(): Float = decodeTaggedFloat(popTag())
    override fun decodeDouble(): Double = decodeTaggedDouble(popTag())
    override fun decodeChar(): Char = decodeTaggedChar(popTag())
    override fun decodeString(): String = decodeTaggedString(popTag())

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int = decodeTaggedEnum(popTag(), enumDescriptor)

    override fun decodeBooleanElement(descriptor: SerialDescriptor, index: Int): Boolean =
        decodeTaggedBoolean(descriptor.getTag(index))

    override fun decodeByteElement(descriptor: SerialDescriptor, index: Int): Byte =
        decodeTaggedByte(descriptor.getTag(index))

    override fun decodeShortElement(descriptor: SerialDescriptor, index: Int): Short =
        decodeTaggedShort(descriptor.getTag(index))

    override fun decodeIntElement(descriptor: SerialDescriptor, index: Int): Int =
        decodeTaggedInt(descriptor.getTag(index))

    override fun decodeLongElement(descriptor: SerialDescriptor, index: Int): Long =
        decodeTaggedLong(descriptor.getTag(index))

    override fun decodeFloatElement(descriptor: SerialDescriptor, index: Int): Float =
        decodeTaggedFloat(descriptor.getTag(index))

    override fun decodeDoubleElement(descriptor: SerialDescriptor, index: Int): Double =
        decodeTaggedDouble(descriptor.getTag(index))

    override fun decodeCharElement(descriptor: SerialDescriptor, index: Int): Char =
        decodeTaggedChar(descriptor.getTag(index))

    override fun decodeStringElement(descriptor: SerialDescriptor, index: Int): String =
        decodeTaggedString(descriptor.getTag(index))

    override fun decodeInlineElement(
        descriptor: SerialDescriptor,
        index: Int
    ): Decoder = decodeTaggedInline(descriptor.getTag(index))

    private fun useDefer(
        sub: String,
        descriptor: SerialDescriptor,
        index: Int,
    ) = map.containsKey(sub) && map[sub]!!.let { v ->
        val vl = v.lowercase()
        v.startsWith(config.deferMarker) || (descriptor.getElementDescriptor(index).kind is StructureKind && v != config.nullMarker && vl != "true" && vl != "false")
    }

    override fun <T : Any?> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?
    ): T {
        val sub = descriptor.getTag(index)
        if(useDefer(sub, descriptor, index)) {
            val withoutPrefix = map[sub]!!.removePrefix(config.deferMarker)
            try {
                return config.deferredFormat.decodeFromString(deserializer, withoutPrefix)
            } catch(e: IllegalArgumentException) {
                throw SerializationException("${errorContext()}Failed to parse key $sub '$withoutPrefix' as a ${descriptor.getElementDescriptor(index).serialName}: ${e.message}", e)
            }
        } else {
            return tagBlock(descriptor.getTag(index)) { decodeSerializableValue(deserializer) }
        }
    }

    override fun <T : Any> decodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T?>,
        previousValue: T?
    ): T? {
        val sub = descriptor.getTag(index)
        if(useDefer(sub, descriptor, index)) {
            val withoutPrefix = map[sub]!!.removePrefix(config.deferMarker)
            try {
                return config.deferredFormat.decodeFromString(deserializer, withoutPrefix)
            } catch(e: IllegalArgumentException) {
                throw SerializationException("${errorContext()}Failed to parse key $sub '$withoutPrefix' as a ${descriptor.getElementDescriptor(index).serialName}: ${e.message}", e)
            }
        } else {
            return tagBlock(descriptor.getTag(index)) {
                val isNullabilitySupported = deserializer.descriptor.isNullable
                if (isNullabilitySupported || decodeNotNullMark()) decodeSerializableValue(
                    deserializer
                ) else decodeNull()
            }
        }
    }

    private fun <E> tagBlock(tag: String, block: () -> E): E {
        pushTag(tag)
        val r = block()
        if (!flag) {
            popTag()
        }
        flag = false
        return r
    }

    private val tagStack = arrayListOf<String>()
    private val currentTagOrNull: String?
        get() = tagStack.lastOrNull()

    private fun pushTag(name: String) {
        tagStack.add(name)
    }

    private fun copyTagsTo(other: StringDeferringDecoder) {
        other.tagStack.addAll(tagStack)
    }

    private var flag = false

    private fun popTag(): String {
        if(tagStack.isEmpty()) return "value"
        val r = tagStack.removeAt(tagStack.lastIndex)
        flag = true
        return r
    }

    fun SerialDescriptor.getTag(index: Int): String = nested(elementName(this, index))

    private fun nested(nestedName: String): String = composeName(currentTagOrNull ?: "", nestedName)
    fun elementName(descriptor: SerialDescriptor, index: Int): String = descriptor.getElementName(index)
    fun composeName(parentName: String, childName: String): String =
        if (parentName.isEmpty()) childName else "$parentName.$childName"
}

