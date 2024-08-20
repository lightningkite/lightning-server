@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.lightningdb

import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.internal.GeneratedSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlin.reflect.KClass

sealed interface VirtualType {
    val serializer: KSerializer<*>
    val annotations: List<SerializableAnnotation>
}

@Serializable
data class VirtualStructure(
    val serialName: String,
    override val annotations: List<SerializableAnnotation>,
    val fields: List<VirtualField>,
) : KSerializer<VirtualInstance>, VirtualType {
    val defaultInstance by lazy { VirtualInstance(this, fields.map { it.default }) }
    operator fun invoke(): VirtualInstance = defaultInstance
    operator fun invoke(map: Map<VirtualField, Any?> = mapOf()): VirtualInstance {
        val fields = fields.mapTo(ArrayList()) { it.default }
        for ((key, value) in map) fields[key.index] = value
        return VirtualInstance(this, fields)
    }

    override val serializer: KSerializer<*> get() = this
    val serializableProperties: Array<SerializableProperty<VirtualInstance, Any?>> by lazy {
        fields.map {
            SerializableProperty.FromVirtualField(
                it
            )
        }.toTypedArray()
    }

    companion object {
        val registeredGenericTypes = HashMap<String, KClass<*>>()
        val registeredTypes = HashMap<String, KSerializer<*>>()
        val cache = HashMap<String, VirtualStructure>()
        var module: SerializersModule = ClientModule
    }

    @Transient
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(serialName) {
        for (field in fields)
            element(
                elementName = field.name,
                descriptor = field.type.serializer().descriptor,
                annotations = listOf(),
                isOptional = field.optional
            )
    }

    override fun deserialize(decoder: Decoder): VirtualInstance {
        val values = Array<Any?>(fields.size) { null }
        val s = decoder.beginStructure(descriptor)
        while (true) {
            val index = s.decodeElementIndex(descriptor)
            if (index == CompositeDecoder.DECODE_DONE) break
            if (index == CompositeDecoder.UNKNOWN_NAME) break
            val f = fields[index]
            if (f.type.isNullable) {
                values[index] = s.decodeNullableSerializableElement(
                    descriptor,
                    index,
                    f.type.serializer(),
                    null
                )
            } else {
                values[index] = s.decodeSerializableElement(
                    descriptor,
                    index,
                    f.type.serializer(),
                    null
                )
            }
        }
        s.endStructure(descriptor)
        return VirtualInstance(this, values.asList())
    }

    override fun serialize(encoder: Encoder, value: VirtualInstance) {
        val s = encoder.beginStructure(descriptor)
        for ((index, field) in fields.withIndex()) {
            val v = value.values[index]
            if (v != field.default || s.shouldEncodeElementDefault(descriptor, index)) {
                s.encodeSerializableElement(descriptor, index, field.type.serializer(), v)
            }
        }
        s.endStructure(descriptor)
    }

    override fun toString(): String = "virtual data class $serialName(${fields.joinToString()})"
}

@Serializable
data class VirtualEnum(
    val serialName: String,
    override val annotations: List<SerializableAnnotation>,
    val options: List<VirtualEnumOption>,
) : VirtualType, KSerializer<VirtualEnumValue> {
    private val map = options.associateBy { it.name }
    override val serializer: KSerializer<*> get() = this
    override fun toString(): String = "Virtual $serialName { ${options.joinToString()} }"
    override val descriptor: SerialDescriptor = object : SerialDescriptor {
        @ExperimentalSerializationApi
        override val elementsCount: Int get() = options.size

        @ExperimentalSerializationApi
        override val kind: SerialKind = SerialKind.ENUM

        @ExperimentalSerializationApi
        override val serialName: String = this@VirtualEnum.serialName

        @ExperimentalSerializationApi
        override fun getElementAnnotations(index: Int): List<Annotation> = listOf()

        @ExperimentalSerializationApi
        override fun getElementDescriptor(index: Int): SerialDescriptor = this

        @ExperimentalSerializationApi
        override fun getElementIndex(name: String): Int = map.get(name)?.index ?: CompositeDecoder.UNKNOWN_NAME

        @ExperimentalSerializationApi
        override fun getElementName(index: Int): String = options[index].name

        @ExperimentalSerializationApi
        override fun isElementOptional(index: Int): Boolean = false
    }

    override fun deserialize(decoder: Decoder): VirtualEnumValue {
        return VirtualEnumValue(this, decoder.decodeEnum(descriptor))
    }

    override fun serialize(encoder: Encoder, value: VirtualEnumValue) {
        encoder.encodeEnum(descriptor, value.index)
    }

    companion object {
        val cache = HashMap<String, VirtualEnum>()
    }
}

@Serializable
data class VirtualEnumOption(
    val name: String,
    val annotations: List<SerializableAnnotation>,
    val index: Int
)

class VirtualEnumValue(
    val enum: VirtualEnum,
    val index: Int,
) {
    override fun toString(): String = enum.options[index].name
}

data class VirtualInstance(
    val type: VirtualStructure,
    val values: List<Any?> = type.fields.map { it.default }
) {
    override fun toString(): String =
        "${type.serialName}(${values.zip(type.fields).joinToString { "${it.second.name}=${it.first}" }})"
}

@Serializable
data class VirtualField(
    val index: Int,
    val name: String,
    val type: VirtualTypeReference,
    val optional: Boolean,
    val annotations: List<SerializableAnnotation>,
    val defaultJson: String? = null,
) {
    companion object {
        val defaultsJson by lazy { Json { serializersModule = VirtualStructure.module } }
    }

    @Transient
    val default: Any? by lazy {
        defaultJson?.let { defaultsJson.decodeFromString(type.serializer(), it) } ?: type.serializer().default()
    }

    override fun toString(): String = "$name: $type"
}

private const val ARRAY_NAME = "kotlin.Array"
private const val ARRAY_LIST_NAME = "kotlin.collections.ArrayList"
private const val LINKED_HASH_SET_NAME = "kotlin.collections.LinkedHashSet"
private const val HASH_SET_NAME = "kotlin.collections.HashSet"
private const val LINKED_HASH_MAP_NAME = "kotlin.collections.LinkedHashMap"
private const val HASH_MAP_NAME = "kotlin.collections.HashMap"

val skipTypes = setOf(
    "com.lightningkite.lightningdb.Condition",
    "com.lightningkite.lightningdb.Modification",
    "com.lightningkite.lightningdb.DataClassPathPartial",
    "com.lightningkite.lightningdb.SortPart",
)

@Serializable
data class VirtualTypeReference(
    val serialName: String,
    val arguments: List<VirtualTypeReference>,
    val isNullable: Boolean
) {
    init {
        if (serialName.endsWith("?")) throw Exception()
    }

    override fun toString(): String =
        serialName + (arguments.takeUnless { it.isEmpty() }?.joinToString(", ", "<", ">") { it.toString() }
            ?: "") + (if (isNullable) "?" else "")

    @Suppress("UNCHECKED_CAST")
    fun serializer(): KSerializer<Any?> {
        try {
            return when (serialName) {
                ARRAY_NAME,
                ARRAY_LIST_NAME -> ListSerializer(arguments[0].serializer()).let { if (isNullable) it.nullable else it } as KSerializer<Any?>

                LINKED_HASH_SET_NAME, HASH_SET_NAME -> SetSerializer(
                    arguments[0].serializer(),
                ).let { if (isNullable) it.nullable else it } as KSerializer<Any?>

                LINKED_HASH_MAP_NAME, HASH_MAP_NAME -> MapSerializer(
                    arguments[0].serializer(),
                    arguments[1].serializer()
                ).let { if (isNullable) it.nullable else it } as KSerializer<Any?>

                "com.lightningkite.lightningdb.Condition" -> Condition.serializer(arguments[0].serializer())
                    .let { if (isNullable) it.nullable else it } as KSerializer<Any?>

                "com.lightningkite.lightningdb.Modification" -> Modification.serializer(arguments[0].serializer())
                    .let { if (isNullable) it.nullable else it } as KSerializer<Any?>

                "com.lightningkite.lightningdb.DataClassPathPartial" -> DataClassPathSerializer(arguments[0].serializer())
                    .let { if (isNullable) it.nullable else it } as KSerializer<Any?>

                "com.lightningkite.lightningdb.SortPart" -> SortPart.serializer(arguments[0].serializer())
                    .let { if (isNullable) it.nullable else it } as KSerializer<Any?>

                else -> VirtualStructure.registeredGenericTypes[serialName]?.let {
                    VirtualStructure.module.serializer(
                        it,
                        arguments.map { it.serializer() },
                        isNullable
                    )
                }
                    ?: (VirtualStructure.registeredTypes[serialName] as? KSerializer<Any>)?.let { s -> (if (isNullable) s.nullable else s) as KSerializer<Any?> }
                    ?: (VirtualStructure.cache[serialName] as? KSerializer<Any>)?.let { s -> (if (isNullable) s.nullable else s) as KSerializer<Any?> }
                    ?: throw Exception("$serialName is not registered in either the registeredTypes or registeredGenericTypes")
            }
        } catch (e: Exception) {
            throw Exception("Could not find serializer for ${this}", e)
        }
    }
}

@Serializable
data class SerializableAnnotation(
    val fqn: String,
    val values: Map<String, SerializableAnnotationValue>
) {
    companion object {
        private val __do_not_use_externally__parsers = HashMap<String, (Annotation) -> SerializableAnnotation>()
        fun <T : Annotation> parser(name: String, handler: (T) -> SerializableAnnotation) {
            @Suppress("UNCHECKED_CAST")
            __do_not_use_externally__parsers[name] = handler as ((Annotation) -> SerializableAnnotation)
        }

        fun parseOrNull(annotation: Annotation): SerializableAnnotation? {
            val fqn = annotation.toString().removePrefix("@").substringBefore("(")
            return __do_not_use_externally__parsers[fqn]?.invoke(annotation)
        }
    }
}

val KSerializer<*>.serializableAnnotations: List<SerializableAnnotation>
    get() = if(this is VirtualType) this.annotations else descriptor.annotations.mapNotNull { SerializableAnnotation.parseOrNull(it) }

fun KSerializer<*>.getElementSerializableAnnotations(index: Int): List<SerializableAnnotation> =
    if(this is VirtualStructure) this.fields[index].annotations
    else descriptor.getElementAnnotations(index).mapNotNull { SerializableAnnotation.parseOrNull(it) }

@Serializable
sealed class SerializableAnnotationValue {
    @Serializable
    data object NullValue : SerializableAnnotationValue()
    @Serializable
    data class BooleanValue(val value: Boolean) : SerializableAnnotationValue()
    @Serializable
    data class ByteValue(val value: Byte) : SerializableAnnotationValue()
    @Serializable
    data class ShortValue(val value: Short) : SerializableAnnotationValue()
    @Serializable
    data class IntValue(val value: Int) : SerializableAnnotationValue()
    @Serializable
    data class LongValue(val value: Long) : SerializableAnnotationValue()
    @Serializable
    data class FloatValue(val value: Float) : SerializableAnnotationValue()
    @Serializable
    data class DoubleValue(val value: Double) : SerializableAnnotationValue()
    @Serializable
    data class CharValue(val value: Char) : SerializableAnnotationValue()
    @Serializable
    data class StringValue(val value: String) : SerializableAnnotationValue()
    @Serializable
    data class ClassValue(val fqn: String) : SerializableAnnotationValue()
    @Serializable
    data class ArrayValue(val value: List<SerializableAnnotationValue>) : SerializableAnnotationValue()
    companion object {
        @OptIn(InternalSerializationApi::class)
        operator fun invoke(value: Any?): SerializableAnnotationValue {
            return when (value) {
                null -> NullValue
                is Boolean -> BooleanValue(value)
                is Byte -> ByteValue(value)
                is Short -> ShortValue(value)
                is Int -> IntValue(value)
                is Long -> LongValue(value)
                is Float -> FloatValue(value)
                is Double -> DoubleValue(value)
                is Char -> CharValue(value)
                is String -> StringValue(value)
                is KClass<*> -> ClassValue(value.serializerOrNull()?.descriptor?.serialName ?: "")
                is BooleanArray -> ArrayValue(value.map { invoke(it) })
                is ByteArray -> ArrayValue(value.map { invoke(it) })
                is ShortArray -> ArrayValue(value.map { invoke(it) })
                is IntArray -> ArrayValue(value.map { invoke(it) })
                is LongArray -> ArrayValue(value.map { invoke(it) })
                is FloatArray -> ArrayValue(value.map { invoke(it) })
                is DoubleArray -> ArrayValue(value.map { invoke(it) })
                is CharArray -> ArrayValue(value.map { invoke(it) })
                is Array<*> -> ArrayValue(value.map { invoke(it) })
                else -> NullValue
            }
        }
    }
}

@OptIn(InternalSerializationApi::class)
fun KSerializer<*>.registerVirtualTypes() {
    val o = (this.nullElement() ?: this)
    if (VirtualStructure.registeredTypes.containsKey(o.descriptor.serialName)) return
    if (VirtualStructure.registeredGenericTypes.containsKey(o.descriptor.serialName)) return
    println("Registering ${o.descriptor.serialName} for virtual types")
    if (o is GeneratedSerializer<*>) {
        if (o.typeParametersSerializers().isNotEmpty()) {
            o.typeParametersSerializers().forEach { it.registerVirtualTypes() }
            println("WARNING: Could not register {${o.descriptor.serialName}")
//            VirtualStructure.registeredGenericTypes[o.descriptor.serialName] = o.descriptor.capturedKClass!!
        } else {
            VirtualStructure.registeredTypes[o.descriptor.serialName] = o
        }
        o.tryChildSerializers()?.forEach { it.registerVirtualTypes() }
    } else {
        when (o.descriptor.kind) {
            StructureKind.LIST -> arrayOf(o.innerElement())
            StructureKind.MAP -> arrayOf(o.innerElement(), o.innerElement2())
            else -> VirtualStructure.registeredTypes[o.descriptor.serialName] = o
        }
    }
}

@OptIn(InternalSerializationApi::class)
fun KSerializer<*>.makeVirtualType(): VirtualType? {
    registerVirtualTypes()
    return this.nullElement()?.makeVirtualType() ?: when (descriptor.kind) {
        StructureKind.CLASS -> {
            if (descriptor.serialName in skipTypes) null
            else VirtualStructure.cache.getOrPut(descriptor.serialName) {
                VirtualStructure(
                    serialName = descriptor.serialName,
                    annotations = descriptor.annotations.mapNotNull { SerializableAnnotation.parseOrNull(it) },
                    fields = serializableProperties?.mapIndexed { index, it ->
                        VirtualField(
                            index = index,
                            name = it.name,
                            type = it.serializer.virtualTypeReference(),
                            optional = false,
                            annotations = it.annotations.mapNotNull { SerializableAnnotation.parseOrNull(it) },
                            defaultJson = it.default?.let { default ->
                                @Suppress("UNCHECKED_CAST")
                                VirtualField.defaultsJson.encodeToString(it.serializer as KSerializer<Any?>, default)
                            }
                        )
                    } ?: (this as? GeneratedSerializer<*>)?.let {
                        println("WARNING: No serializable properties found for ${descriptor.serialName}")
                        val gen = it.childSerializers()
                        (0..<descriptor.elementsCount).map {
                            val d = descriptor.getElementDescriptor(it)
                            VirtualField(
                                index = it,
                                name = descriptor.getElementName(it),
                                type = VirtualTypeReference(
                                    serialName = d.nonNullOriginal.serialName,
                                    arguments = gen[it].let { it.nullElement() ?: it }.tryTypeParameterSerializers3()
                                        ?.map { it.virtualTypeReference() } ?: listOf(),
                                    isNullable = d.isNullable
                                ),
                                optional = descriptor.isElementOptional(it),
                                annotations = listOf()
                            )
                        }
                    } ?: run {
                        println("WARNING: No serializable properties OR gen found for ${descriptor.serialName}")
                        (0..<descriptor.elementsCount).map {
                            val d = descriptor.getElementDescriptor(it)
                            VirtualField(
                                index = it,
                                name = descriptor.getElementName(it),
                                type = VirtualTypeReference(
                                    serialName = d.nonNullOriginal.serialName,
                                    arguments = listOf(),
                                    isNullable = d.isNullable
                                ),
                                optional = descriptor.isElementOptional(it),
                                annotations = listOf()
                            )
                        }
                    }
                )
            }
        }

        SerialKind.ENUM -> {
            VirtualEnum.cache.getOrPut(descriptor.serialName) {
                VirtualEnum(
                    serialName = descriptor.serialName,
                    annotations = descriptor.annotations.mapNotNull { SerializableAnnotation.parseOrNull(it) },
                    options = (0..<descriptor.elementsCount).map {
                        VirtualEnumOption(
                            name = descriptor.getElementName(it),
                            annotations = descriptor.getElementAnnotations(it)
                                .mapNotNull { SerializableAnnotation.parseOrNull(it) },
                            index = it
                        )
                    }
                )
            }
        }

        else -> null
    }
}

@OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
fun KSerializer<*>.virtualTypeReference(): VirtualTypeReference {
    registerVirtualTypes()
    val o = nullElement() ?: this
    return VirtualTypeReference(
        serialName = o.descriptor.serialName,
        arguments = o.tryTypeParameterSerializers3()?.map { it.virtualTypeReference() } ?: listOf(),
        isNullable = descriptor.isNullable
    )
}