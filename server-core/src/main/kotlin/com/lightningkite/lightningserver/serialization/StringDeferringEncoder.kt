package com.lightningkite.lightningserver.serialization

import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.modules.SerializersModule

/*
 * These classes are intended to be used only within the kotlinx.serialization.
 * They neither do have stable API, nor internal invariants and are changed without any warnings.
 */
@OptIn(InternalSerializationApi::class)
public class StringDeferringEncoder(
    val config: StringDeferringConfig,
    val steadyHeaders: Boolean = true,
) : Encoder, CompositeEncoder {
    override val serializersModule: SerializersModule
        get() = config.serializersModule
    val map: MutableMap<String, String> = mutableMapOf()

    fun encode(value: Any): String = value.toString()

    fun headers(root: SerialDescriptor): List<String> = buildList {
        assert(steadyHeaders)
        val visited = HashSet<String>()
        fun sub(descriptor: SerialDescriptor, prefix: String = "") {
            if(!visited.add(descriptor.serialName.removeSuffix("?"))) return
            for (i in 0 until descriptor.elementsCount) {
                val name = prefix + descriptor.getElementName(i)
                var childDesc = descriptor.getElementDescriptor(i)
                if(childDesc.kind == SerialKind.CONTEXTUAL)
                    childDesc = serializersModule.getContextualDescriptor(childDesc) ?: throw SerializationException("No contextual serializer found for ${childDesc.serialName}")

                when {
                    (childDesc.kind == StructureKind.LIST ||
                            childDesc.kind == StructureKind.MAP ||
                            childDesc.kind == PolymorphicKind.OPEN ||
                            childDesc.kind == PolymorphicKind.SEALED) -> {
                        add(name)
                    }
                    childDesc.kind == SerialKind.ENUM -> add(name)
                    childDesc.kind == StructureKind.OBJECT -> Unit
                    childDesc.elementsCount > 0 -> {
                        add(name)
                        sub(childDesc, "$name.")
                    }

                    else -> add(name)
                }
            }
        }
        sub(root)
    }

    override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
        when(serializer.descriptor.kind) {
            PolymorphicKind.SEALED,
            PolymorphicKind.OPEN,
            StructureKind.LIST,
            StructureKind.MAP -> if(steadyHeaders)
                encodeTaggedValue(popTag(), config.deferMarker + config.deferredFormat.encodeToString(serializer, value))
            else
                serializer.serialize(this, value)
            StructureKind.CLASS -> if(steadyHeaders && seenStack.contains(serializer.descriptor.serialName))
                encodeTaggedValue(popTag(), config.deferMarker + config.deferredFormat.encodeToString(serializer, value))
            else {
                seenStack.add(serializer.descriptor.serialName)
                serializer.serialize(this, value)
                seenStack.removeLast()
            }
            else -> serializer.serialize(this, value)
        }
    }

    fun encodeTaggedValue(tag: String, value: Any) {
        map[tag] = encode(value)
    }

    fun encodeTaggedNull(tag: String) {
        // ignore nulls in output
        map[tag] = config.nullMarker
    }

    fun encodeTaggedEnum(tag: String, enumDescriptor: SerialDescriptor, ordinal: Int) {
        map[tag] = encode(enumDescriptor.getElementName(ordinal))
    }

    // ---- API ----

    fun encodeTaggedNonNullMark() {}
    fun encodeTaggedInt(tag: String, value: Int): Unit = encodeTaggedValue(tag, value)
    fun encodeTaggedByte(tag: String, value: Byte): Unit = encodeTaggedValue(tag, value)
    fun encodeTaggedShort(tag: String, value: Short): Unit = encodeTaggedValue(tag, value)
    fun encodeTaggedLong(tag: String, value: Long): Unit = encodeTaggedValue(tag, value)
    fun encodeTaggedFloat(tag: String, value: Float): Unit = encodeTaggedValue(tag, value)
    fun encodeTaggedDouble(tag: String, value: Double): Unit = encodeTaggedValue(tag, value)
    fun encodeTaggedBoolean(tag: String, value: Boolean): Unit = encodeTaggedValue(tag, value)
    fun encodeTaggedChar(tag: String, value: Char): Unit = encodeTaggedValue(tag, value)
    fun encodeTaggedString(tag: String, value: String): Unit = encodeTaggedValue(tag, if(value.startsWith(config.deferMarker)) config.deferMarker + config.deferredFormat.encodeToString(String.serializer(), value) else value)

    fun encodeTaggedInline(tag: String): Encoder =
        this.apply { pushTag(tag) }

    override fun encodeInline(descriptor: SerialDescriptor): Encoder =
        encodeTaggedInline(popTag())

    // ---- Implementation of low-level API ----

    private fun encodeElement(desc: SerialDescriptor, index: Int): Boolean {
        val tag = desc.getTag(index)
        pushTag(tag)
        return true
    }

    override fun encodeNotNullMark(): Unit = encodeTaggedNonNullMark()
    override fun encodeNull(): Unit = encodeTaggedNull(popTag())
    override fun encodeBoolean(value: Boolean): Unit = encodeTaggedBoolean(popTag(), value)
    override fun encodeByte(value: Byte): Unit = encodeTaggedByte(popTag(), value)
    override fun encodeShort(value: Short): Unit = encodeTaggedShort(popTag(), value)
    override fun encodeInt(value: Int): Unit = encodeTaggedInt(popTag(), value)
    override fun encodeLong(value: Long): Unit = encodeTaggedLong(popTag(), value)
    override fun encodeFloat(value: Float): Unit = encodeTaggedFloat(popTag(), value)
    override fun encodeDouble(value: Double): Unit = encodeTaggedDouble(popTag(), value)
    override fun encodeChar(value: Char): Unit = encodeTaggedChar(popTag(), value)
    override fun encodeString(value: String): Unit = encodeTaggedString(popTag(), value)

    override fun encodeEnum(
        enumDescriptor: SerialDescriptor,
        index: Int
    ): Unit = encodeTaggedEnum(popTag(), enumDescriptor, index)

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder = this

    override fun endStructure(descriptor: SerialDescriptor) {
        if (tagStack.isNotEmpty()) {
            popTag()
        }
    }

    override fun encodeBooleanElement(descriptor: SerialDescriptor, index: Int, value: Boolean): Unit =
        encodeTaggedBoolean(descriptor.getTag(index), value)

    override fun encodeByteElement(descriptor: SerialDescriptor, index: Int, value: Byte): Unit =
        encodeTaggedByte(descriptor.getTag(index), value)

    override fun encodeShortElement(descriptor: SerialDescriptor, index: Int, value: Short): Unit =
        encodeTaggedShort(descriptor.getTag(index), value)

    override fun encodeIntElement(descriptor: SerialDescriptor, index: Int, value: Int): Unit =
        encodeTaggedInt(descriptor.getTag(index), value)

    override fun encodeLongElement(descriptor: SerialDescriptor, index: Int, value: Long): Unit =
        encodeTaggedLong(descriptor.getTag(index), value)

    override fun encodeFloatElement(descriptor: SerialDescriptor, index: Int, value: Float): Unit =
        encodeTaggedFloat(descriptor.getTag(index), value)

    override fun encodeDoubleElement(descriptor: SerialDescriptor, index: Int, value: Double): Unit =
        encodeTaggedDouble(descriptor.getTag(index), value)

    override fun encodeCharElement(descriptor: SerialDescriptor, index: Int, value: Char): Unit =
        encodeTaggedChar(descriptor.getTag(index), value)

    override fun encodeStringElement(descriptor: SerialDescriptor, index: Int, value: String): Unit =
        encodeTaggedString(descriptor.getTag(index), value)

    override fun encodeInlineElement(
        descriptor: SerialDescriptor,
        index: Int
    ): Encoder {
        return encodeTaggedInline(descriptor.getTag(index))
    }

    override fun <T : Any?> encodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T
    ) {
        if (encodeElement(descriptor, index))
            encodeSerializableValue(serializer, value)
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun <T : Any> encodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T?
    ) {
        if (encodeElement(descriptor, index))
            encodeNullableSerializableValue(serializer, value)
    }

    private val seenStack = arrayListOf<String>()

    private val tagStack = arrayListOf<String>()
    private val currentTagOrNull: String?
        get() = tagStack.lastOrNull()

    private fun pushTag(name: String) {
        tagStack.add(name)
    }

    private fun popTag(): String =
        if (tagStack.isNotEmpty())
            tagStack.removeAt(tagStack.lastIndex)
        else
            "value"

    fun SerialDescriptor.getTag(index: Int): String = nested(elementName(this, index))
    private fun nested(nestedName: String): String = composeName(currentTagOrNull ?: "", nestedName)
    fun elementName(descriptor: SerialDescriptor, index: Int): String = descriptor.getElementName(index)
    fun composeName(parentName: String, childName: String): String =
        if (parentName.isEmpty()) childName else "$parentName.$childName"
}
