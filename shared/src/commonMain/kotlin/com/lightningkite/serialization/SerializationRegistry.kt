package com.lightningkite.serialization

import com.lightningkite.GeoCoordinateArraySerializer
import com.lightningkite.OffsetDateTimeIso8601Serializer
import com.lightningkite.ZonedDateTimeIso8601Serializer
import com.lightningkite.lightningserver.files.ServerFileSerializer
import kotlinx.serialization.*
import kotlinx.serialization.builtins.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.nonNullOriginal
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.internal.GeneratedSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.SerializersModuleCollector
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.time.Duration

class SerializationRegistry(val module: SerializersModule) {
    private val direct = HashMap<String, KSerializer<*>>()
    private val factory = HashMap<String, (Array<KSerializer<*>>) -> KSerializer<*>>()
    private val internalVirtualTypes = HashMap<String, VirtualType>()
    val virtualTypes: Map<String, VirtualType> get() = internalVirtualTypes

    companion object {
        val master = SerializationRegistry(ClientModule)
    }

    init {
        module.dumpTo(object : SerializersModuleCollector {
            override fun <T : Any> contextual(
                kClass: KClass<T>,
                provider: (typeArgumentsSerializers: List<KSerializer<*>>) -> KSerializer<*>
            ) {
                val sample = ContextualSerializer(
                    kClass,
                    fallbackSerializer = null,
                    typeArgumentsSerializers = Array(10) { NothingSerializer() })
                factory[sample.descriptor.serialName] = { provider(it.toList()) }
            }

            override fun <Base : Any, Sub : Base> polymorphic(
                baseClass: KClass<Base>,
                actualClass: KClass<Sub>,
                actualSerializer: KSerializer<Sub>
            ) {
            }

            override fun <Base : Any> polymorphicDefaultDeserializer(
                baseClass: KClass<Base>,
                defaultDeserializerProvider: (className: String?) -> DeserializationStrategy<Base>?
            ) {
            }

            override fun <Base : Any> polymorphicDefaultSerializer(
                baseClass: KClass<Base>,
                defaultSerializerProvider: (value: Base) -> SerializationStrategy<Base>?
            ) {
            }

        })
    }

    fun register(serializer: KSerializer<*>) {
        direct[serializer.descriptor.serialName] = serializer
    }

    fun register(name: String, make: (Array<KSerializer<Nothing>>) -> KSerializer<*>) {
        @Suppress("UNCHECKED_CAST")
        factory[name] = make as (Array<KSerializer<*>>) -> KSerializer<*>
    }

    fun <T: VirtualType> register(type: T): T {
        internalVirtualTypes[type.serialName] = type
        when (type) {
            is VirtualEnum -> direct[type.serialName] = type
            is VirtualStruct -> if (type.parameters.isEmpty()) direct[type.serialName] = type.Concrete(this, arrayOf())
            else factory[type.serialName] = { type.serializer(this, it) }

            else -> factory[type.serialName] = { type.serializer(this, it) }
        }
        return type
    }

    @Suppress("UNCHECKED_CAST")
    operator fun get(name: String, arguments: Array<KSerializer<*>>): KSerializer<Any?>? =
        direct[name] as? KSerializer<Any?> ?: factory[name]?.invoke(arguments) as? KSerializer<Any?>

    init {
        // These are all very safe built-in classes, thus we just register them here.
        register(Unit.serializer())
        register(Boolean.serializer())
        register(Byte.serializer())
        register(UByte.serializer())
        register(Short.serializer())
        register(UShort.serializer())
        register(Int.serializer())
        register(UInt.serializer())
        register(Long.serializer())
        register(ULong.serializer())
        register(Float.serializer())
        register(Double.serializer())
        register(Char.serializer())
        register(String.serializer())
        register(Duration.serializer())
        register(InstantIso8601Serializer)
        register(LocalDateIso8601Serializer)
        register(LocalTimeIso8601Serializer)
        register(LocalDateTimeIso8601Serializer)
        register(UUIDSerializer)
        register(OffsetDateTimeIso8601Serializer)
        register(ZonedDateTimeIso8601Serializer)
        register(ServerFileSerializer)
        register(GeoCoordinateArraySerializer)
        register(ListSerializer(NothingSerializer()).descriptor.serialName) { ListSerializer(it[0]) }
        register(SetSerializer(NothingSerializer()).descriptor.serialName) { SetSerializer(it[0]) }
        register(MapSerializer(NothingSerializer(), NothingSerializer()).descriptor.serialName) {
            MapSerializer(
                it[0],
                it[1]
            )
        }
        register(
            MapEntrySerializer(
                NothingSerializer(),
                NothingSerializer()
            ).descriptor.serialName
        ) { MapEntrySerializer(it[0], it[1]) }
        register(PairSerializer(NothingSerializer(), NothingSerializer()).descriptor.serialName) {
            PairSerializer(
                it[0],
                it[1]
            )
        }
        register(
            TripleSerializer(
                NothingSerializer(),
                NothingSerializer(),
                NothingSerializer()
            ).descriptor.serialName
        ) { TripleSerializer(it[0], it[1], it[2]) }
    }

    private class GenericPlaceholderSerializer(val infoSource: String, val index: Int = 0) : KSerializer<Nothing> {
        var used: Boolean = false

        @OptIn(ExperimentalSerializationApi::class)
        val wraps = NothingSerializer()

        @OptIn(ExperimentalSerializationApi::class)
        override val descriptor: SerialDescriptor by lazy {
            used = true
            SerialDescriptor(
                ('A' + index).toString(),
                wraps.descriptor
            )
        }

        override fun deserialize(decoder: Decoder): Nothing {
            throw Error("Someone is trying to actually use the GenericPlaceholderSerializer.  This was used to produce info for $infoSource for parameter $index, nothing more.")
        }

        override fun serialize(encoder: Encoder, value: Nothing) {
            throw Error("Someone is trying to actually use the GenericPlaceholderSerializer.  This was used to produce info for $infoSource for parameter $index, nothing more.")
        }
    }

    fun registerVirtualDeep(type: KSerializer<*>) {
        if(registerVirtual(type) != null) {
            type.tryChildSerializers()?.forEach { registerVirtual(it) }
        }
        type.tryTypeParameterSerializers3()?.forEach { registerVirtual(it) }
    }
    fun registerVirtual(type: KSerializer<*>): VirtualType? {
        type.nullElement()?.let { return registerVirtual(it) }
        return if (type.descriptor.serialName !in direct && type.descriptor.serialName !in factory) {
            // virtualize me!
            if(type.tryTypeParameterSerializers3().isNullOrEmpty()) registerVirtualWithoutTypeParameters(type)
            else registerVirtualWithTypeParameters(type.descriptor.serialName, master.factory[type.descriptor.serialName] ?: throw IllegalStateException("${type.descriptor.serialName} not registered in master"))
        } else null
    }

    @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
    fun virtualize(matching: (String) -> Boolean): SerializationRegistry {
        val new = SerializationRegistry(module)
        for ((key, value) in direct) {
            // Never virtualize base types
            if (!matching(key)) continue
            if (new.direct.containsKey(key)) continue
            if (new.factory.containsKey(key)) continue
            if(new.registerVirtualWithoutTypeParameters(value) == null) new.register(value)
        }
        for ((key, generator) in factory) {
            // Never virtualize base types
            if (!matching(key)) continue
            if (new.direct.containsKey(key)) continue
            if (new.factory.containsKey(key)) continue

            val generics = Array(10) { GenericPlaceholderSerializer(key, it) }
            val value = generator(generics.map { it }.toTypedArray())
            @Suppress("UNCHECKED_CAST")
            if(new.registerVirtualWithTypeParameters(value.descriptor.serialName, generator) == null) new.register(key, generator as (Array<KSerializer<Nothing>>) -> KSerializer<*>)
        }
        return new
    }

    @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
    private fun registerVirtualWithoutTypeParameters(
        value: KSerializer<*>
    ): VirtualType? {
        return when (value.descriptor.kind) {
            StructureKind.CLASS -> register(VirtualStruct(
                serialName = value.descriptor.serialName,
                annotations = value.descriptor.annotations.mapNotNull { SerializableAnnotation.parseOrNull(it) },
                fields = value.serializableProperties?.mapIndexed { index, it ->
                    VirtualField(
                        index = index,
                        name = it.name,
                        type = it.serializer.virtualTypeReference(this),
                        optional = false,
                        annotations = it.annotations.mapNotNull { SerializableAnnotation.parseOrNull(it) },
                        defaultJson = it.default?.let { default ->
                            @Suppress("UNCHECKED_CAST")
                            defjs.encodeToString(it.serializer as KSerializer<Any?>, default)
                        }
                    )
                } ?: (value as? GeneratedSerializer<*>)?.let {
                    it.typeParametersSerializers()
                    println("WARNING: No serializable properties found for ${value.descriptor.serialName}")
                    val gen = it.childSerializers()
                    (0..<value.descriptor.elementsCount).map {
                        val d = value.descriptor.getElementDescriptor(it)
                        VirtualField(
                            index = it,
                            name = value.descriptor.getElementName(it),
                            type = gen[it].virtualTypeReference(this),
                            optional = value.descriptor.isElementOptional(it),
                            annotations = listOf()
                        )
                    }
                } ?: run {
                    println("WARNING: No serializable properties OR gen found for ${value.descriptor.serialName}")
                    (0..<value.descriptor.elementsCount).map {
                        val d = value.descriptor.getElementDescriptor(it)
                        VirtualField(
                            index = it,
                            name = value.descriptor.getElementName(it),
                            type = VirtualTypeReference(
                                serialName = d.nonNullOriginal.serialName,
                                arguments = listOf(),
                                isNullable = d.isNullable
                            ),
                            optional = value.descriptor.isElementOptional(it),
                            annotations = listOf()
                        )
                    }
                },
                parameters = listOf()
            )
            )

            SerialKind.ENUM -> register(VirtualEnum(
                serialName = value.descriptor.serialName,
                annotations = value.descriptor.annotations.mapNotNull { SerializableAnnotation.parseOrNull(it) },
                options = (0..<value.descriptor.elementsCount).map {
                    VirtualEnumOption(
                        name = value.descriptor.getElementName(it),
                        annotations = value.descriptor.getElementAnnotations(it)
                            .mapNotNull { SerializableAnnotation.parseOrNull(it) },
                        index = it
                    )
                }
            ) as VirtualType)

            else -> null
        }
    }

    @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
    private fun registerVirtualWithTypeParameters(
        key: String,
        generator: (Array<KSerializer<*>>) -> KSerializer<*>
    ): VirtualType? {
        val generics = Array(10) { GenericPlaceholderSerializer(key, it) }
        val value = generator(generics.map { it }.toTypedArray())
        return when (value.descriptor.kind) {
            StructureKind.CLASS -> register(VirtualStruct(
                serialName = value.descriptor.serialName,
                annotations = value.descriptor.annotations.mapNotNull {
                    SerializableAnnotation.parseOrNull(
                        it
                    )
                },
                fields = value.serializableProperties?.mapIndexed { index, it ->
                    VirtualField(
                        index = index,
                        name = it.name,
                        type = it.serializer.virtualTypeReference(this),
                        optional = false,
                        annotations = it.annotations.mapNotNull { SerializableAnnotation.parseOrNull(it) },
                        defaultJson = it.default?.let { default ->
                            @Suppress("UNCHECKED_CAST")
                            defjs.encodeToString(it.serializer as KSerializer<Any?>, default)
                        }
                    )
                } ?: (value as? GeneratedSerializer<*>)?.let {
                    it.typeParametersSerializers()
                    println("WARNING: No serializable properties found for ${value.descriptor.serialName}")
                    val gen = it.childSerializers()
                    (0..<value.descriptor.elementsCount).map {
                        val d = value.descriptor.getElementDescriptor(it)
                        VirtualField(
                            index = it,
                            name = value.descriptor.getElementName(it),
                            type = gen[it].virtualTypeReference(this),
                            optional = value.descriptor.isElementOptional(it),
                            annotations = listOf()
                        )
                    }
                } ?: run {
                    println("WARNING: No serializable properties OR gen found for ${value.descriptor.serialName}")
                    (0..<value.descriptor.elementsCount).map {
                        val d = value.descriptor.getElementDescriptor(it)
                        VirtualField(
                            index = it,
                            name = value.descriptor.getElementName(it),
                            type = VirtualTypeReference(
                                serialName = d.nonNullOriginal.serialName,
                                arguments = listOf(),
                                isNullable = d.isNullable
                            ),
                            optional = value.descriptor.isElementOptional(it),
                            annotations = listOf()
                        )
                    }
                },
                parameters = generics.asSequence().filter { it.used }.map {
                    VirtualTypeParameter(name = it.descriptor.serialName)
                }.toList()
            )
            )

            SerialKind.ENUM -> register(VirtualEnum(
                serialName = value.descriptor.serialName,
                annotations = value.descriptor.annotations.mapNotNull {
                    SerializableAnnotation.parseOrNull(
                        it
                    )
                },
                options = (0..<value.descriptor.elementsCount).map {
                    VirtualEnumOption(
                        name = value.descriptor.getElementName(it),
                        annotations = value.descriptor.getElementAnnotations(it)
                            .mapNotNull { SerializableAnnotation.parseOrNull(it) },
                        index = it
                    )
                }
            ) as VirtualType)
            else -> null
        }
    }
}

private val defjs = Json { serializersModule = DefaultDecoder.serializersModule }
