package com.lightningkite.lightningserver.db

import com.lightningkite.UUID
import com.lightningkite.lightningdb.*
import com.lightningkite.serialization.*
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.toJavaUuid
import com.lightningkite.toKotlinUuid
import kotlinx.datetime.*
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.internal.TaggedDecoder
import kotlinx.serialization.internal.TaggedEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.javatime.*
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import kotlinx.datetime.serializers.InstantIso8601Serializer
import kotlinx.datetime.serializers.LocalDateIso8601Serializer
import kotlinx.datetime.serializers.LocalDateTimeIso8601Serializer
import kotlinx.datetime.serializers.LocalTimeIso8601Serializer
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

class DbMapLikeFormat(val serializersModule: SerializersModule = Serialization.module) {
    fun <T> encode(serializer: KSerializer<T>, value: T, it: UpdateBuilder<*>, path: List<String> = listOf("")) {
        val columns = it.targets.flatMap { it.columns }.map {
            @Suppress("UNCHECKED_CAST")
            it as Column<Any?>
        }.associateBy { it.name }
        DbLikeMapEncoder(
            serializersModule,
            { k, v ->
                it[columns[k] ?: throw IllegalStateException("Could not find key $k in columns")] = v
            },
            {}
        ).also { it.startWith(path) }.encodeSerializableValue(serializer, value)
    }

    fun <T> encode(
        serializer: KSerializer<T>,
        value: T,
        out: MutableMap<String, Any?> = LinkedHashMap<String, Any?>(),
        path: List<String> = listOf(""),
    ): Map<String, Any?> {
        DbLikeMapEncoder(
            serializersModule,
            out
        ).also { it.startWith(path) }.encodeSerializableValue(serializer, value)
        return out
    }

    fun <T> decode(serializer: KSerializer<T>, map: Map<String, Any?>, path: List<String> = listOf("")): T {
        return DbLikeMapDecoder(serializersModule, map, serializer.descriptor).also { it.startWith(path) }
            .decodeSerializableValue(serializer)
    }

    fun <T> decode(serializer: KSerializer<T>, map: ResultRow, path: List<String> = listOf("")): T {
        val columns = map.fieldIndex.keys.mapNotNull {
            @Suppress("UNCHECKED_CAST")
            it as? Column<Any?>
        }.associateBy { it.name }
        return DbLikeMapDecoder(
            serializersModule,
            keys = columns.keys,
            getter = { k -> map[columns[k]!!] },
            descriptor = serializer.descriptor
        ).also { it.startWith(path) }.decodeSerializableValue(serializer)
    }
}

@InternalSerializationApi
@OptIn(ExperimentalSerializationApi::class)
public abstract class UnderscoreNamedValueEncoder : TaggedEncoder<String>() {
    var lastMapKey: String = ""
    final override fun SerialDescriptor.getTag(index: Int): String =
        when (kind) {
            StructureKind.CLASS -> nested(elementName(this, index))
            StructureKind.MAP -> {
                lastMapKey = elementName(this, index)
                currentTagOrNull ?: ""
            }

            else -> (currentTagOrNull ?: "")
        }

    protected fun nested(nestedName: String): String = composeName(currentTagOrNull ?: "", nestedName)
    protected open fun elementName(descriptor: SerialDescriptor, index: Int): String = descriptor.getElementName(index)
    protected open fun composeName(parentName: String, childName: String): String =
        if (parentName.isEmpty()) childName else if (childName.isEmpty()) parentName else "${parentName}__$childName"

    fun startWith(path: List<String>) {
        if (path.isNotEmpty()) pushTag(path.fold("", ::composeName))
    }
}

@InternalSerializationApi
@OptIn(ExperimentalSerializationApi::class)
public abstract class UnderscoreNamedValueDecoder : TaggedDecoder<String>() {
    var lastMapKey: String = ""
    final override fun SerialDescriptor.getTag(index: Int): String =
        when (kind) {
            StructureKind.CLASS -> nested(elementName(this, index))
            StructureKind.MAP -> {
                lastMapKey = elementName(this, index)
                currentTagOrNull ?: ""
            }

            else -> (currentTagOrNull ?: "")
        }

    protected fun nested(nestedName: String): String = composeName(currentTagOrNull ?: "", nestedName)
    protected open fun elementName(desc: SerialDescriptor, index: Int): String = desc.getElementName(index)
    protected open fun composeName(parentName: String, childName: String): String =
        if (parentName.isEmpty()) childName else if (childName.isEmpty()) parentName else "${parentName}__$childName"

    fun startWith(path: List<String>) {
        if (path.isNotEmpty()) pushTag(path.fold("", ::composeName))
    }
}

@OptIn(InternalSerializationApi::class)
class DbLikeMapDecoder(
    override val serializersModule: SerializersModule,
    val keys: Set<String>,
    val getter: (String) -> Any?,
    val descriptor: SerialDescriptor,
) :
    UnderscoreNamedValueDecoder() {
    constructor(serializersModule: SerializersModule, map: Map<String, Any?>, descriptor: SerialDescriptor) : this(
        serializersModule,
        map.keys,
        { map[it] },
        descriptor
    )

    private var currentIndex = 0
    private val size = descriptor.elementsCount

    override fun decodeTaggedValue(tag: String): Any = getter(tag)!!
    override fun decodeTaggedChar(tag: String): Char = (getter(tag)!! as String).first()

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        while (currentIndex < size) {
            val name = descriptor.getTag(currentIndex++)
            if (keys.any {
                    it.startsWith(name) && (it.length == name.length || it[name.length] == '_')
                }) return currentIndex - 1
        }
        return CompositeDecoder.DECODE_DONE
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        return when (descriptor.kind) {
            is StructureKind.CLASS -> DbLikeMapDecoder(
                serializersModule,
                keys,
                getter,
                descriptor
            ).also { copyTagsTo(it) }

            is StructureKind.LIST -> {
                val size = (getter(keys.first() { it.startsWith(currentTag) }) as List<Any?>).size
                return ListDecodeMapper(decodeInstance = { index ->
                    DbLikeMapDecoder(
                        serializersModule = serializersModule,
                        keys = keys,
                        getter = { (getter(it) as List<Any?>)[index] },
                        descriptor = descriptor
                    ).also { copyTagsTo(it) }
                }, count = size)
            }

            is StructureKind.MAP -> {
                val size = (getter(keys.first() { it.startsWith(currentTag) }) as List<Any?>).size
                return MapDecodeMapper(
                    decodeKey = { index ->
                        DbLikeMapDecoder(
                            serializersModule = serializersModule,
                            keys = keys,
                            getter = {
                                (getter(it) as? List<Any?>
                                    ?: throw IllegalStateException("Could not find $it as an array"))[index]
                            },
                            descriptor = descriptor
                        ).also { copyTagsTo(it) }
                    },
                    decodeValue = { index ->
                        DbLikeMapDecoder(
                            serializersModule = serializersModule,
                            keys = keys,
                            getter = {
                                (getter(it) as? List<Any?>
                                    ?: throw IllegalStateException("Could not find $it as an array"))[index]
                            },
                            descriptor = descriptor
                        ).also { copyTagsTo(it); it.pushTag(it.composeName(it.currentTag, "value")) }
                    },
                    count = size
                )
            }

            else -> throw Error()
        }
    }

    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
        @Suppress("UNCHECKED_CAST")
        return when ((deserializer as? KSerializer<T>)?.nullElement() ?: deserializer) {
            UUIDSerializer -> getter(popTag()).let { it as java.util.UUID }.toKotlinUuid() as T
            LocalDateIso8601Serializer, com.lightningkite.serialization.LocalDateIso8601Serializer -> getter(popTag()).let { it as LocalDate }.toKotlinLocalDate() as T
            InstantIso8601Serializer, com.lightningkite.serialization.InstantIso8601Serializer -> getter(popTag()).let { it as java.time.Instant }.toKotlinInstant() as T
            DurationSerializer, com.lightningkite.serialization.DurationSerializer -> getter(popTag()).let { it as Duration }.toKotlinDuration() as T
            LocalDateTimeIso8601Serializer, com.lightningkite.serialization.LocalDateTimeIso8601Serializer -> getter(popTag()).let { it as LocalDateTime }.toKotlinLocalDateTime() as T
            LocalTimeIso8601Serializer, com.lightningkite.serialization.LocalTimeIso8601Serializer -> getter(popTag()) .let { it as LocalTime }.toKotlinLocalTime() as T

            else -> super.decodeSerializableValue(deserializer)
        }
    }

    override fun decodeNotNullMark(): Boolean {
        if (keys.contains(composeName(currentTag, "exists")))
            return (getter(composeName(currentTag, "exists")) as? Boolean
                ?: throw IllegalArgumentException("No exists field found for $currentTag"))
        else
            return getter(currentTag) != null
    }
}

@OptIn(InternalSerializationApi::class)
class ListDecodeMapper(val decodeInstance: (index: Int) -> Decoder, val count: Int) : CompositeDecoder {
    var index = 0
    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        if (index >= count) return CompositeDecoder.DECODE_DONE
        return index
    }

    fun getDecoder(): Decoder {
        return decodeInstance(index++)
    }

    override val serializersModule: SerializersModule
        get() = EmptySerializersModule()

    override fun decodeBooleanElement(descriptor: SerialDescriptor, index: Int): Boolean {
        return getDecoder().decodeBoolean()
    }

    override fun decodeByteElement(descriptor: SerialDescriptor, index: Int): Byte {
        return getDecoder().decodeByte()
    }

    override fun decodeCharElement(descriptor: SerialDescriptor, index: Int): Char {
        return getDecoder().decodeChar()
    }

    override fun decodeDoubleElement(descriptor: SerialDescriptor, index: Int): Double {
        return getDecoder().decodeDouble()
    }

    override fun decodeFloatElement(descriptor: SerialDescriptor, index: Int): Float {
        return getDecoder().decodeFloat()
    }

    override fun decodeInlineElement(descriptor: SerialDescriptor, index: Int): Decoder {
        return getDecoder().decodeInline(descriptor.getElementDescriptor(index))
    }

    override fun decodeIntElement(descriptor: SerialDescriptor, index: Int): Int {
        return getDecoder().decodeInt()
    }

    override fun decodeLongElement(descriptor: SerialDescriptor, index: Int): Long {
        return getDecoder().decodeLong()
    }

    @ExperimentalSerializationApi
    override fun <T : Any> decodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T?>,
        previousValue: T?,
    ): T? {
        return getDecoder().decodeNullableSerializableValue(deserializer)
    }

    override fun <T> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?,
    ): T {
        return getDecoder().decodeSerializableValue(deserializer)
    }

    override fun decodeShortElement(descriptor: SerialDescriptor, index: Int): Short {
        return getDecoder().decodeShort()
    }

    override fun decodeStringElement(descriptor: SerialDescriptor, index: Int): String {
        return getDecoder().decodeString()
    }

    @ExperimentalSerializationApi
    override fun decodeSequentially(): Boolean = true

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = count

    override fun endStructure(descriptor: SerialDescriptor) {
    }
}

@OptIn(InternalSerializationApi::class)
class MapDecodeMapper(
    val decodeKey: (index: Int) -> Decoder,
    val decodeValue: (index: Int) -> Decoder,
    val count: Int,
) : CompositeDecoder {
    var index = 0
    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        if (index >= count) return CompositeDecoder.DECODE_DONE
        return index
    }

    fun getDecoder(): Decoder {
        return if (index % 2 == 0) decodeKey(index++ / 2) else decodeValue(index++ / 2)
    }

    override val serializersModule: SerializersModule
        get() = EmptySerializersModule()

    override fun decodeBooleanElement(descriptor: SerialDescriptor, index: Int): Boolean {
        return getDecoder().decodeBoolean()
    }

    override fun decodeByteElement(descriptor: SerialDescriptor, index: Int): Byte {
        return getDecoder().decodeByte()
    }

    override fun decodeCharElement(descriptor: SerialDescriptor, index: Int): Char {
        return getDecoder().decodeChar()
    }

    override fun decodeDoubleElement(descriptor: SerialDescriptor, index: Int): Double {
        return getDecoder().decodeDouble()
    }

    override fun decodeFloatElement(descriptor: SerialDescriptor, index: Int): Float {
        return getDecoder().decodeFloat()
    }

    override fun decodeInlineElement(descriptor: SerialDescriptor, index: Int): Decoder {
        return getDecoder().decodeInline(descriptor.getElementDescriptor(index))
    }

    override fun decodeIntElement(descriptor: SerialDescriptor, index: Int): Int {
        return getDecoder().decodeInt()
    }

    override fun decodeLongElement(descriptor: SerialDescriptor, index: Int): Long {
        return getDecoder().decodeLong()
    }

    @ExperimentalSerializationApi
    override fun <T : Any> decodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T?>,
        previousValue: T?,
    ): T? {
        return getDecoder().decodeNullableSerializableValue(deserializer)
    }

    override fun <T> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?,
    ): T {
        return getDecoder().decodeSerializableValue(deserializer)
    }

    override fun decodeShortElement(descriptor: SerialDescriptor, index: Int): Short {
        return getDecoder().decodeShort()
    }

    override fun decodeStringElement(descriptor: SerialDescriptor, index: Int): String {
        return getDecoder().decodeString()
    }

    @ExperimentalSerializationApi
    override fun decodeSequentially(): Boolean = true

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = count

    override fun endStructure(descriptor: SerialDescriptor) {
    }
}

@OptIn(InternalSerializationApi::class)
class DbLikeMapEncoder(
    override val serializersModule: SerializersModule,
    val writer: (String, Any?) -> Unit,
    val end: () -> Unit,
) : UnderscoreNamedValueEncoder() {
    constructor(serializersModule: SerializersModule, map: MutableMap<String, Any?>) : this(
        serializersModule = serializersModule,
        writer = { k, v -> map[k] = v },
        end = {}
    )

    override fun encodeTaggedValue(tag: String, value: Any) {
        writer(tag, value)
    }

    override fun encodeTaggedChar(tag: String, value: Char) {
        writer(tag, value.toString())
    }

    override fun encodeTaggedNull(tag: String) {
        writer(tag, null)
    }

    override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
        when ((serializer as? KSerializer<T>)?.nullElement() ?: serializer) {
            UUIDSerializer -> writer(popTag(), (value as com.lightningkite.UUID).toJavaUuid())
            LocalDateIso8601Serializer, com.lightningkite.serialization.LocalDateIso8601Serializer -> writer(popTag(), (value as kotlinx.datetime.LocalDate).toJavaLocalDate())
            InstantIso8601Serializer, com.lightningkite.serialization.InstantIso8601Serializer -> writer(popTag(), (value as Instant).toJavaInstant())
            DurationSerializer, com.lightningkite.serialization.DurationSerializer -> writer(popTag(), (value as kotlin.time.Duration).toJavaDuration())
                //LocalDateTimeSerializer -> writer(popTag(), value)
            LocalDateTimeIso8601Serializer, com.lightningkite.serialization.LocalDateTimeIso8601Serializer -> writer(popTag(), (value as kotlinx.datetime.LocalDateTime).toJavaLocalDateTime())
            LocalTimeIso8601Serializer, com.lightningkite.serialization.LocalTimeIso8601Serializer -> writer(popTag(), (value as kotlinx.datetime.LocalTime).toJavaLocalTime())

            else -> super.encodeSerializableValue(serializer, value)
        }
    }

    override fun <T : Any> encodeNullableSerializableValue(serializer: SerializationStrategy<T>, value: T?) {
        if (value == null) {
            when (serializer.descriptor.kind) {
                StructureKind.CLASS -> writer(composeName(popTag(), "exists"), false)
                StructureKind.LIST -> encodeNull()
                StructureKind.MAP -> {
                    val tag = popTag()
                    writer(tag, null)
                    writer(composeName(tag, "value"), null)
                }

                else -> encodeNull()
            }
        } else {
            if (serializer.descriptor.kind == StructureKind.CLASS)
                writer(composeName(currentTag, "exists"), true)
            encodeSerializableValue(serializer, value)
        }
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        return when (descriptor.kind) {
            is StructureKind.CLASS -> this
            is StructureKind.LIST -> {
                val t = currentTag
                val lists =
                    descriptor.getElementDescriptor(0).columnType().map { it.key.joinToString("__") }.associateWith {
                        val l = ArrayList<Any?>()
                        writer(composeName(t, it), l)
                        l
                    }
                DbLikeMapEncoder(
                    serializersModule = serializersModule,
                    writer = { k, v ->
                        lists.getValue(k).add(v)
                    },
                    end = { endStructure(descriptor) }
                )
            }

            is StructureKind.MAP -> {
                val t = currentTag
                val keyLists =
                    descriptor.getElementDescriptor(0).columnType().map { it.key.joinToString("__") }.associateWith {
                        val l = ArrayList<Any?>()
                        writer(composeName(t, it), l)
                        l
                    }
                val valueLists =
                    descriptor.getElementDescriptor(1).columnType().map { it.key.joinToString("__") }.associateWith {
                        val l = ArrayList<Any?>()
                        writer(composeName(composeName(t, it), "value"), l)
                        l
                    }
                var isKey = true
                DbLikeMapEncoder(
                    serializersModule = serializersModule,
                    writer = { k, v ->
                        if (isKey) {
                            keyLists.getValue(k).add(v)
                        } else {
                            valueLists.getValue(k).add(v)
                        }
                        isKey = !isKey
                    },
                    end = { endStructure(descriptor) }
                )
            }

            else -> throw Error()
        }
    }

    override fun endEncode(descriptor: SerialDescriptor) {
        end()
    }
}
