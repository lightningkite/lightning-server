package com.lightningkite.lightningserver.scim

import com.lightningkite.CaselessStringSerializer
import com.lightningkite.EmailAddressSerializer
import com.lightningkite.LowercaseOnSerialize
import com.lightningkite.TrimLowercaseOnSerialize
import com.lightningkite.TrimmedCaselessStringSerializer
import com.lightningkite.lightningdb.MultipleReferences
import com.lightningkite.lightningdb.References
import com.lightningkite.lightningdb.Unique
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.exceptions.RawHttpStatusException
import com.lightningkite.lightningserver.files.ExternalServerFileSerializer
import com.lightningkite.lightningserver.files.ServerFileSerializer
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.serialization.InstantIso8601Serializer
import com.lightningkite.serialization.descriptionOrDisplayName
import com.lightningkite.serialization.listElement
import com.lightningkite.serialization.nullElement
import com.lightningkite.serialization.serializableProperties
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.StringFormat
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.capturedKClass
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import kotlin.collections.component1
import kotlin.collections.component2


fun KSerializer<*>.scimType(): ScimType = when (descriptor.serialName) {
    kotlinx.datetime.serializers.InstantIso8601Serializer.descriptor.serialName,
    InstantIso8601Serializer.descriptor.serialName -> ScimType.dateTime

    ExternalServerFileSerializer.descriptor.serialName,
    ServerFileSerializer.descriptor.serialName -> ScimType.reference

    else -> when (val kind = descriptor.kind) {
        PrimitiveKind.BOOLEAN -> ScimType.boolean
        PrimitiveKind.DOUBLE -> ScimType.decimal
        PrimitiveKind.FLOAT -> ScimType.decimal
        PrimitiveKind.BYTE,
        PrimitiveKind.INT,
        PrimitiveKind.LONG,
        PrimitiveKind.SHORT -> ScimType.integer

        PrimitiveKind.STRING,
        PrimitiveKind.CHAR -> ScimType.string

        SerialKind.ENUM -> ScimType.string
        StructureKind.CLASS -> ScimType.complex
        StructureKind.LIST -> listElement()!!.scimType()
        else -> throw NotImplementedError("We haven't implemented ${kind}")
    }
}

fun KSerializer<*>.attributes(): Set<ScimAttributeDefinition> = serializableProperties!!.map {
    val serializer = it.serializer.let { it.nullElement() ?: it }.uncontextualize(Serialization.json.serializersModule)
    val innerSerializer = serializer.let { it.listElement() ?: it }.let { it.nullElement() ?: it }.uncontextualize(Serialization.json.serializersModule)
    ScimAttributeDefinition(
        name = it.name.removePrefix("_"),
        type = serializer.scimType(),
        subAttributes = innerSerializer.attributes(),
        multiValued = serializer.descriptor.kind == StructureKind.LIST,
        description = it.descriptionOrDisplayName,
        required = it.default != null,
        canonicalValues = if (innerSerializer.descriptor.kind == SerialKind.ENUM)
            innerSerializer.descriptor.elementNames.toList()
        else null,
        caseExact = when (innerSerializer) {
            TrimLowercaseOnSerialize,
            CaselessStringSerializer,
            TrimmedCaselessStringSerializer,
            EmailAddressSerializer,
            LowercaseOnSerialize -> false

            else -> true
        },
        mutability = when {
            it.annotations.any { it is ScimReadOnly } -> ScimMutability.readOnly
            it.annotations.any { it is ScimWriteOnly } -> ScimMutability.writeOnly
            else -> ScimMutability.readWrite
        },
        returned = ScimReturned.default,
        uniqueness = when {
            it.annotations.any { it is Unique } -> ScimUniqueness.server
            else -> ScimUniqueness.none
        },
        referenceTypes = when (innerSerializer.descriptor.serialName) {
            ExternalServerFileSerializer.descriptor.serialName,
            ServerFileSerializer.descriptor.serialName -> setOf("external")
            else -> it.annotations.flatMap { annotation ->
                when(annotation) {
                    is ScimReference -> annotation.types.toList()
                    is References -> annotation.references.let(::listOf)
                    is MultipleReferences -> annotation.references.let(::listOf)
                    else -> emptyList()
                }
            }.map { Serialization.json.serializersModule.serializer(it, listOf(), false).scimSchemaUri }.toSet()
        }
    )
}.toSet()

private fun KSerializer<*>.uncontextualize(module: SerializersModule = Serialization.json.serializersModule): KSerializer<*> {
    return if (this.descriptor.kind == SerialKind.CONTEXTUAL) {
        module.getContextual(
            descriptor.capturedKClass ?: throw IllegalStateException("No captured KClass found for $descriptor")
        )
            ?: throw IllegalStateException("No contextual serializer found for ${descriptor.capturedKClass!!.qualifiedName}")
    } else this
}

fun SerializationStrategy<*>.isScimResource() = descriptor.annotations.any { it is ScimSchemaUri }
fun DeserializationStrategy<*>.isScimResource() = descriptor.annotations.any { it is ScimSchemaUri }

private fun SerialDescriptor.fmap() = (0..<elementsCount).mapNotNull { i ->
    val uri = getElementDescriptor(i).annotations.filterIsInstance<ScimExtension>().firstOrNull()?.uri ?: return@mapNotNull null
    getElementName(i) to uri
}.associate { it }

private fun JsonEncoder.scimIntercept(scimRoot: String): InterceptableJsonEncoder =
    this as? InterceptableJsonEncoder ?: InterceptableJsonEncoder(scimRoot, this)
private class InterceptableJsonEncoder(val scimRoot: String, val wraps: JsonEncoder): JsonEncoder by wraps {
    override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
        if(serializer.isScimResource()) {
            val uri = serializer.descriptor.annotations.filterIsInstance<ScimSchemaUri>().first().uri
            val fmap = serializer.descriptor.fmap()
            val asJson = Serialization.json.encodeToJsonElement(serializer, value) as JsonObject
            // WAH HA HA HA
            val modified = buildJsonObject {
                putJsonArray("schemas") {
                    add(uri)
                    // Extensions
                    serializer.descriptor.elementDescriptors
                        .mapNotNull { it.annotations.filterIsInstance<ScimExtension>().firstOrNull()?.uri }
                        .forEach { add(it) }
                }
                putJsonObject("meta") {
                    put("resourceType", uri.substringAfterLast(':'))
                    put("created", asJson["createdAt"]!!)
                    put("lastModified", asJson["modifiedAt"]!!)
                    put("location", scimRoot + "/" + uri.substringAfterLast(':') + "/" + asJson["_id"]!!.jsonPrimitive.content)
//                        put("version")
                }
                for((key, value) in asJson.entries) {
                    when(key) {
                        "createdAt", "modifiedAt" -> {}
                        "_id" -> put("id", value)
                        else -> put(fmap[key] ?: key, value)
                    }
                }
            }
            wraps.encodeSerializableValue(JsonObject.serializer(), modified)
        } else wraps.encodeSerializableValue(serializer, value)
    }

    override fun beginStructure(descriptor: SerialDescriptor) = (wraps.beginStructure(descriptor) as JsonEncoder).scimIntercept(scimRoot)
    override fun beginCollection(descriptor: SerialDescriptor, collectionSize: Int): CompositeEncoder = (wraps.beginCollection(descriptor, collectionSize) as JsonEncoder).scimIntercept(scimRoot)
    override fun encodeInline(descriptor: SerialDescriptor): Encoder = (wraps.encodeInline(descriptor) as JsonEncoder).scimIntercept(scimRoot)
    override fun encodeInlineElement(descriptor: SerialDescriptor, index: Int): Encoder = (wraps.encodeInlineElement(descriptor, index) as JsonEncoder).scimIntercept(scimRoot)
}

private fun JsonDecoder.scimIntercept(scimRoot: String): InterceptableJsonDecoder =
    this as? InterceptableJsonDecoder ?: InterceptableJsonDecoder(scimRoot, this)
private class InterceptableJsonDecoder(val scimRoot: String, val wraps: JsonDecoder): JsonDecoder by wraps {
    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
        if(deserializer.isScimResource()) {
            val fmap = deserializer.descriptor.fmap()
            val value = wraps.decodeSerializableValue(JsonObject.serializer())
            // WAH HA HA HA
            val modified = buildJsonObject {
                for((key, value) in value.entries) {
                    put(key, value)
                }
            }
            return Serialization.json.decodeFromJsonElement(deserializer, modified)
        } else return wraps.decodeSerializableValue(deserializer)
    }

    override fun beginStructure(descriptor: SerialDescriptor) = (wraps.beginStructure(descriptor) as JsonDecoder).scimIntercept(scimRoot)
    override fun decodeInline(descriptor: SerialDescriptor) = (wraps.decodeInline(descriptor) as JsonDecoder).scimIntercept(scimRoot)
    override fun decodeInlineElement(descriptor: SerialDescriptor, index: Int): Decoder = (wraps.decodeInline(descriptor) as JsonDecoder).scimIntercept(scimRoot)
}

class ScimHackerySerializer<T>(val scimRoot: String, val inner: KSerializer<T>): KSerializer<T> {
    override val descriptor: SerialDescriptor = inner.descriptor  // run silent

    override fun serialize(
        encoder: Encoder,
        value: T
    ) {
        InterceptableJsonEncoder(scimRoot, encoder as JsonEncoder).encodeSerializableValue(inner, value)
    }

    override fun deserialize(decoder: Decoder): T {
        return InterceptableJsonDecoder(scimRoot, decoder as JsonDecoder).decodeSerializableValue(inner)
    }

}

lateinit var scimRoot: String
private val Serialization_jsonScim by lazy {
    object: StringFormat {
        override fun <T> encodeToString(
            serializer: SerializationStrategy<T>,
            value: T
        ): String {
            return Serialization.json.encodeToString(ScimHackerySerializer(scimRoot, serializer as KSerializer<T>), value)
        }

        override fun <T> decodeFromString(
            deserializer: DeserializationStrategy<T>,
            string: String
        ): T {
            return Serialization.json.decodeFromString(ScimHackerySerializer(scimRoot, deserializer as KSerializer<T>), string)
        }

        override val serializersModule: SerializersModule get() = Serialization.json.serializersModule
    }
}
val Serialization.Companion.jsonScim get() = Serialization_jsonScim
private val JsonScimContentType = ContentType("application", "scim+json")
val ContentType.Application.JsonScim get() = JsonScimContentType

class ScimErrorException(val error: ScimError): RawHttpStatusException(error.detail, null) {
    override suspend fun toResponse(request: HttpRequest): HttpResponse = HttpResponse(
        HttpContent.Text(
            Serialization.jsonScim.encodeToString(ScimError.serializer(), error),
            ContentType.Application.JsonScim
        )
    )
}