package com.lightningkite.lightningserver.jsonschema

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.db.ModelRestEndpoints
import com.lightningkite.lightningserver.files.UploadEarlyEndpoint
import com.lightningkite.lightningserver.humanize
import com.lightningkite.lightningserver.kabobCase
import com.lightningkite.lightningserver.routes.fullUrl
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.typed.Documentable
import com.lightningkite.lightningserver.typed.docGroup
import kotlinx.serialization.*
import kotlinx.serialization.builtins.ArraySerializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import kotlin.collections.Iterable
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.asSequence
import kotlin.collections.associate
import kotlin.collections.filter
import kotlin.collections.filterIsInstance
import kotlin.collections.firstOrNull
import kotlin.collections.iterator
import kotlin.collections.listOf
import kotlin.collections.map
import kotlin.collections.mapOf
import kotlin.collections.mapValues
import kotlin.collections.mutableMapOf
import kotlin.collections.mutableSetOf
import kotlin.collections.plus
import kotlin.collections.set
import kotlin.collections.take
import kotlin.collections.toList
import kotlin.collections.toSet
import kotlin.reflect.KClass

@Serializable
data class LightningServerSchema(
    val uploadEarlyEndpoint: String? = null,
    val definitions: Map<String, JsonSchemaType>,
    val endpoints: List<LightningServerSchemaEndpoint>,
    val models: Map<String, LightningServerSchemaModel>,
)

@Serializable
data class LightningServerSchemaModel(
    val collectionName: String,
    @SerialName("\$ref") val ref: String? = null,
    val conditionRef: String? = null,
    val modificationRef: String? = null,
    val url: String,
    val searchFields: List<String>,
    val tableColumns: List<String>,
    val titleFields: List<String>,
)

@Serializable
data class LightningServerSchemaEndpoint(
    val group: String? = null,
    val method: String,
    val path: String,
    val routes: Map<String, JsonSchemaType>,
    val input: JsonSchemaType,
    val output: JsonSchemaType,
)

val lightningServerSchema: LightningServerSchema by lazy {
    val builder = JsonSchemaBuilder(Serialization.json)
    Documentable.endpoints.flatMap {
        sequenceOf(it.inputType, it.outputType) + it.routeTypes.values.asSequence()
    }.distinct().forEach { builder.get(it.descriptor) }
    LightningServerSchema(
        definitions = builder.definitions,
        uploadEarlyEndpoint = UploadEarlyEndpoint.default?.path?.fullUrl(),
        endpoints = Documentable.endpoints.map {
            LightningServerSchemaEndpoint(
                group = it.docGroup,
                method = it.route.method.toString(),
                path = it.path.toString(),
                routes = it.routeTypes.mapValues { builder.get(it.value.descriptor) },
                input = builder.get(it.inputType.descriptor),
                output = builder.get(it.outputType.descriptor),
            )
        }.toList(),
        models = ModelRestEndpoints.all.associate {
            it.collectionName.kabobCase() to LightningServerSchemaModel(
                collectionName = it.collectionName.humanize(),
                url = it.path.fullUrl(),
                ref = builder.refString(it.info.serialization.serializer.descriptor),
                conditionRef = builder.refString(Condition.serializer(it.info.serialization.serializer).descriptor),
                modificationRef = builder.refString(Modification.serializer(it.info.serialization.serializer).descriptor),
                searchFields = it.info.serialization.serializer.descriptor.annotations
                    .filterIsInstance<AdminSearchFields>()
                    .firstOrNull()
                    ?.fields?.toList()
                    ?: it.info.serialization.serializer.descriptor.let {
                        (0 until it.elementsCount)
                            .filter { index -> it.getElementDescriptor(index).kind == PrimitiveKind.STRING }
                            .map { index -> it.getElementName(index) }
                    },
                tableColumns = it.info.serialization.serializer.descriptor.annotations
                    .filterIsInstance<AdminTableColumns>()
                    .firstOrNull()
                    ?.fields?.toList()
                    ?: it.info.serialization.serializer.descriptor.let {
                        (0 until it.elementsCount)
                            .take(3)
                            .map { index -> it.getElementName(index) }
                    },
                titleFields = it.info.serialization.serializer.descriptor.annotations
                    .filterIsInstance<AdminTitleFields>()
                    .firstOrNull()
                    ?.fields?.toList()
                    ?: it.info.serialization.serializer.descriptor.elementNames.toSet().let {
                        when {
                            it.contains("name") -> listOf("name")
                            it.contains("key") -> listOf("key")
                            else -> listOf("_id")
                        }
                    }
            )
        }
    )
}

@Serializable
enum class JsonType2(val isPrimitive: Boolean) {
    @SerialName("null")
    NULL(false),

    @SerialName("array")
    ARRAY(false),

    @SerialName("number")
    NUMBER(true),

    @SerialName("integer")
    INTEGER(true),

    @SerialName("string")
    STRING(true),

    @SerialName("boolean")
    BOOLEAN(true),

    @SerialName("object")
    OBJECT(false),
}

@Serializable(JsonType3Serializer::class)
data class JsonType3(val inner: JsonType2, val nullable: Boolean = false)

object JsonType3Serializer : KSerializer<JsonType3> {
    val multi = ArraySerializer(JsonType2.serializer())
    val single = JsonType2.serializer()

    @OptIn(InternalSerializationApi::class)
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("JsonType3", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: JsonType3) {
        if (value.nullable) encoder.encodeSerializableValue(multi, arrayOf(value.inner, JsonType2.NULL))
        else encoder.encodeSerializableValue(single, value.inner)
    }

    override fun deserialize(decoder: Decoder): JsonType3 {
        (decoder as? JsonDecoder)?.let { input ->
            val element = input.decodeJsonElement()
            return if (element is JsonArray) JsonType3(decoder.json.decodeFromJsonElement(single, element[0]), true)
            else JsonType3(decoder.json.decodeFromJsonElement(single, element))
        }
        return JsonType3(JsonType2.serializer().deserialize(decoder))
    }
}

@Serializable
data class JsonSchemaType(
    @SerialName("\$ref") val ref: String? = null,
    val title: String? = null,
    val nullable: Boolean? = null,
    val references: String? = null,
    val description: String? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val mimeType: String? = null,
    val type: JsonType3? = null,
    val pattern: String? = null,
    val options: JsonObject? = null,
    val format: String? = null,
    val items: JsonSchemaType? = null,
    val properties: Map<String, JsonSchemaType>? = null,
    val additionalProperties: JsonSchemaType? = null,
    val required: List<String>? = null,
    val oneOf: List<JsonSchemaType>? = null,
    val const: String? = null,
    val links: List<JsonSchemaTypeLink>? = null,
    val enum: List<String>? = null,
    @SerialName("ui:widget") val uiWidget: String? = null,
)

@Serializable
data class JsonSchemaDefinition(
    @SerialName("\$schema") val schema: String,
    val definitions: Map<String, JsonSchemaType> = mapOf(),
    @SerialName("\$ref") val ref: String? = null,
)

@Serializable
data class JsonSchemaTypeLink(
    val href: String,
    val rel: String,
)

fun Json.schemaDefinitions(types: Iterable<SerialDescriptor>): Map<String, JsonSchemaType> {
    val b = JsonSchemaBuilder(this)
    for (it in types) b.get(it)
    return b.definitions
}

fun Json.schema(type: SerialDescriptor): JsonSchemaDefinition {
    val b = JsonSchemaBuilder(this)
    b.get(type)
    return JsonSchemaDefinition(
        schema = "https://json-schema.org/draft/2019-09/schema",
        definitions = b.definitions,
        ref = "#/definitions/${b.key(type)}"
    )
}

class JsonSchemaBuilder(
    val json: Json,
    val refString: String = "#/definitions/",
    val useNullableProperty: Boolean = false
) {
    val definitions = mutableMapOf<String, JsonSchemaType>()
    val defining = mutableSetOf<String>()
    val overrides = mutableMapOf<String, (SerialDescriptor) -> JsonSchemaType>()
    val annotationHandlers = mutableMapOf<KClass<*>, (JsonSchemaType, Annotation) -> JsonSchemaType>()

    init {
        annotation { it: Description -> copy(description = it.text) }
        annotation { it: IntegerRange -> copy(minimum = it.min.toDouble(), maximum = it.max.toDouble()) }
        annotation { it: FloatRange -> copy(minimum = it.min, maximum = it.max) }
        annotation { it: ExpectedPattern -> copy(pattern = it.pattern) }
        annotation { it: JsonSchemaFormat -> copy(format = it.format) }
        annotation { it: DisplayName -> copy(title = it.text) }
        annotation { it: AdminHidden -> copy(uiWidget = "hidden") }
        annotation { it: Multiline -> copy(uiWidget = "textarea") }
        annotation { it: UiWidget -> copy(uiWidget = it.type) }
        annotation { it: References -> copy(references = key(json.serializersModule.serializer(it.references.java).descriptor)) }
        annotation { it: MultipleReferences ->
            copy(
                items = items!!.copy(
                    references = key(
                        json.serializersModule.serializer(
                            it.references.java
                        ).descriptor
                    )
                )
            )
        }
        annotation { it: MimeType -> copy(mimeType = it.mime) }
        override("com.lightningkite.lightningdb.ServerFile") { it: SerialDescriptor ->
            JsonSchemaType(
                title = "Server File",
                type = JsonType3(JsonType2.STRING),
                format = "file",
                options = buildJsonObject {
                    putJsonObject("upload") {
                        put("upload_handler", "mainUploadHandler")
                        put("auto_upload", true)
                    }
                },
                links = listOf(JsonSchemaTypeLink("{{self}}", "View File"))
            )
        }
        override("java.util.UUID") {
            JsonSchemaType(
                title = "UUID",
                type = JsonType3(JsonType2.STRING),
                format = "uuid",
                pattern = "^[0-9a-fA-F]{8}\\b-[0-9a-fA-F]{4}\\b-[0-9a-fA-F]{4}\\b-[0-9a-fA-F]{4}\\b-[0-9a-fA-F]{12}\$"
            )
        }
        override("java.time.LocalDate") {
            JsonSchemaType(
                title = "Local Date",
                type = JsonType3(JsonType2.STRING),
                format = "date",
                pattern = "^\\d\\d\\d\\d-\\d\\d-\\d\\d$"
            )
        }
        override("java.time.LocalTime") {
            JsonSchemaType(
                title = "Local Time",
                type = JsonType3(JsonType2.STRING),
                format = "time",
                pattern = "^\\d\\d:\\d\\d(:\\d\\d(\\.\\d+)?)?$"
            )
        }
        override("java.time.ZonedDateTime") {
            JsonSchemaType(
                title = "Zoned Date Time",
                type = JsonType3(JsonType2.STRING),
                format = "date-time-zone",
                pattern = "^\\d\\d\\d\\d-\\d\\d-\\d\\dT\\d\\d:\\d\\d(:\\d\\d(\\.\\d+)?)?(([+-]\\d\\d:\\d\\d(\\[[^\\]+]\\])?)|Z)$"
            )
        }
        override("java.time.Instant") {
            JsonSchemaType(
                title = "Instant",
                type = JsonType3(JsonType2.STRING),
                format = "date-time",
                pattern = "^\\d\\d\\d\\d-\\d\\d-\\d\\dT\\d\\d:\\d\\d(:\\d\\d(\\.\\d+)?)?Z$"
            )
        }
        override("com.lightningkite.lightningdb.Condition") {
            val subtype = (0 until it.elementsCount)
                .find { index -> it.getElementName(index) == "Equal" }
                ?.let { index ->
                    it.getElementDescriptor(index).unwrap().serialName.substringBefore('<').substringAfterLast('.')
                        .humanize()
                }
                ?: "Unknown"
            JsonSchemaType(
                title = "Condition for ${subtype}",
                type = JsonType3(JsonType2.OBJECT),
                oneOf = (0 until it.elementsCount).map { index ->
                    val key = it.getElementName(index)
                    JsonSchemaType(
                        title = key,
                        type = JsonType3(JsonType2.OBJECT),
                        properties = mapOf(key to get(it.getElementDescriptor(index), direct = true))
                    )
                }
            )
        }
        override("com.lightningkite.lightningdb.Modification") {
            val subtype = (0 until it.elementsCount)
                .find { index -> it.getElementName(index) == "Assign" }
                ?.let { index ->
                    it.getElementDescriptor(index).unwrap().serialName.substringBefore('<').substringAfterLast('.')
                        .humanize()
                }
                ?: "Unknown"
            JsonSchemaType(
                title = "Modification for ${subtype}",
                type = JsonType3(JsonType2.OBJECT),
                oneOf = (0 until it.elementsCount).map { index ->
                    val key = it.getElementName(index)
                    JsonSchemaType(
                        title = key,
                        type = JsonType3(JsonType2.OBJECT),
                        properties = mapOf(key to get(it.getElementDescriptor(index), direct = true))
                    )
                }
            )
        }
    }

    inline fun <reified T : Annotation> annotation(crossinline handler: JsonSchemaType.(T) -> JsonSchemaType) {
        annotationHandlers[T::class] = { a, b -> a.handler(b as T) }
    }

    inline fun override(key: String, crossinline handler: (SerialDescriptor) -> JsonSchemaType) {
        overrides[key] = { handler(it) }
    }

    val existingKeys1 = HashMap<SerialDescriptor, String>()
    val existingKeys2 = HashMap<String, SerialDescriptor>()
    fun key(serializer: SerialDescriptor): String {
        existingKeys1[serializer]?.let { return it }
        val baseName = serializer.serialName
        var index = 0
        while (true) {
            val name = baseName + (if (index == 0) "" else index.toString())
            if (!existingKeys2.containsKey(name)) {
                existingKeys1[serializer] = name
                existingKeys2[name] = serializer
                return name
            }
            index++
        }
    }

    @OptIn(InternalSerializationApi::class)
    operator fun get(
        serializer: SerialDescriptor,
        annotationsToApply: List<Annotation> = listOf(),
        title: String = "Value",
        direct: Boolean = false,
    ): JsonSchemaType {
        val annos = annotationsToApply + serializer.annotations
        if (serializer.isNullable) {
            val inner = get(serializer.nullElement()!!, annos, title)
            if (useNullableProperty) {
                return inner.copy(nullable = true)
            } else {
                if (inner.type?.inner?.isPrimitive == true) {
                    return inner.copy(type = inner.type.copy(nullable = true))
                }
                return JsonSchemaType(
                    oneOf = listOf(
                        inner.copy(title = title),
                        JsonSchemaType(type = JsonType3(JsonType2.NULL), title = "$title N/A")
                    )
                )
            }
        }

        fun defining(serializer: SerialDescriptor, action: () -> JsonSchemaType): JsonSchemaType {
            if (direct) return action()
            val key = key(serializer)
            if (defining.add(key)) {
                if (serializer.serialName == "Not") throw Exception()
                definitions[key] = action()
            }
            return JsonSchemaType(ref = refString(serializer))
        }
        if (serializer is LazyRenamedSerialDescriptor) {
            return get(serializer.getter(), title = title, annotationsToApply = annotationsToApply)
        }

        val desc = serializer.unwrap()
        overrides[desc.serialName.substringBefore('<')]?.let {
            return defining(desc) { it(desc) }.applyAnnotations(annos)
        }
        return when (desc.kind) {
            PrimitiveKind.BOOLEAN -> JsonSchemaType(type = JsonType3(JsonType2.BOOLEAN)).applyAnnotations(annos)
            PrimitiveKind.BYTE,
            PrimitiveKind.SHORT,
            PrimitiveKind.LONG,
            PrimitiveKind.INT -> JsonSchemaType(type = JsonType3(JsonType2.INTEGER)).applyAnnotations(annos)

            PrimitiveKind.FLOAT,
            PrimitiveKind.DOUBLE,
            -> JsonSchemaType(type = JsonType3(JsonType2.NUMBER)).applyAnnotations(annos)

            PrimitiveKind.CHAR,
            PrimitiveKind.STRING,
            -> JsonSchemaType(type = JsonType3(JsonType2.STRING)).applyAnnotations(annos)

            SerialKind.ENUM -> defining(serializer) {
                JsonSchemaType(
                    title = desc.serialName.substringBefore('<').substringAfterLast('.').humanize(),
                    type = JsonType3(JsonType2.STRING),
                    oneOf = (0 until desc.elementsCount)
                        .map {
                            val value = desc.getElementName(it)
                            JsonSchemaType(
                                title = desc.getElementAnnotations(it).filterIsInstance<DisplayName>()
                                    .firstOrNull()?.text
                                    ?: value.humanize(),
                                const = value
                            )
                        }
                ).applyAnnotations(annos)
            }

            StructureKind.LIST -> JsonSchemaType(
                type = JsonType3(JsonType2.ARRAY),
                items = get(
                    serializer.getElementDescriptor(0), title = title
                )
            ).applyAnnotations(annos)

            StructureKind.MAP -> JsonSchemaType(
                type = JsonType3(JsonType2.OBJECT),
                additionalProperties = get(serializer.getElementDescriptor(1), title = title)
            ).applyAnnotations(annos)

            StructureKind.CLASS -> defining(serializer) {
                JsonSchemaType(
                    title = desc.serialName.substringBefore('<').substringAfterLast('.').humanize(),
                    type = JsonType3(JsonType2.OBJECT),
                    properties = (0 until desc.elementsCount).associate {
                        val propTitle = desc.getElementName(it).humanize()
                        desc.getElementName(it) to get(
                            desc.getElementDescriptor(it),
                            desc.getElementAnnotations(it),
                            propTitle
                        ).copy(
                            title = propTitle
                        )
                    }
                ).applyAnnotations(annos)
            }

            StructureKind.OBJECT -> JsonSchemaType(
                type = JsonType3(JsonType2.OBJECT),
                properties = mapOf()
            ).applyAnnotations(annos)

            PolymorphicKind.SEALED -> JsonSchemaType(
                type = JsonType3(JsonType2.OBJECT),
                properties = mapOf(
                    "type" to JsonSchemaType(
                        type = JsonType3(JsonType2.STRING),
                        oneOf = (0 until desc.elementsCount).map { index ->
                            get(desc.getElementDescriptor(index))
                        }
                    )
                )
            )

            PolymorphicKind.OPEN -> TODO()
            SerialKind.CONTEXTUAL -> throw Error("This should not be reachable - ${desc.serialName} could be unwrapped no further")
        }
    }

    private fun JsonSchemaType.applyAnnotations(annotations: List<Annotation>): JsonSchemaType {
        var current = this
        for (anno in annotations) {
            for (entry in annotationHandlers) {
                if (entry.key.isInstance(anno)) {
                    current = entry.value(current, anno)
                }
            }
        }
        return current
    }

    fun refString(serializer: SerialDescriptor): String = refString + key(serializer)
}

private fun SerialDescriptor.unwrap(): SerialDescriptor {
    if (this is LazyRenamedSerialDescriptor) return this.getter().unwrap()
    if (this.isNullable) return this.nullElement()?.unwrap() ?: this
    if (this.kind == SerialKind.CONTEXTUAL) {
        return Serialization.json.serializersModule.getContextualDescriptor(this)
            ?: throw IllegalStateException("No contextual implementation found for ${this}")
    }
    return this
}
