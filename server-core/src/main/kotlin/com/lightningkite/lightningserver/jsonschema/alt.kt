package com.lightningkite.lightningserver.jsonschema

import com.charleskorn.kaml.Yaml
import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.db.ModelRestEndpoints
import com.lightningkite.lightningserver.files.ExternalServerFileSerializer
import com.lightningkite.lightningserver.files.UploadEarlyEndpoint
import com.lightningkite.lightningserver.humanize
import com.lightningkite.lightningserver.kabobCase
import com.lightningkite.lightningserver.routes.fullUrl
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.typed.Documentable
import com.lightningkite.lightningserver.typed.docGroup
import io.ktor.http.*
import io.ktor.util.reflect.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.ArraySerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.internal.GeneratedSerializer
import kotlinx.serialization.json.*
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
    @SerialName("\$ref") val ref: String? = null,
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
    }.distinct().forEach { builder.get(it) }
    LightningServerSchema(
        definitions = builder.definitions,
        uploadEarlyEndpoint = UploadEarlyEndpoint.default?.path?.fullUrl(),
        endpoints = Documentable.endpoints.map {
            LightningServerSchemaEndpoint(
                group = it.docGroup,
                method = it.route.method.toString(),
                path = it.path.toString(),
                routes = it.routeTypes.mapValues { builder.get(it.value) },
                input = builder.get(it.inputType),
                output = builder.get(it.outputType),
            )
        }.toList(),
        models = ModelRestEndpoints.all.associate {
            it.info.serialization.serializer.descriptor.serialName.substringBefore('<').substringAfterLast('.').kabobCase() to LightningServerSchemaModel(
                url = it.path.fullUrl(),
                ref = builder.refString(it.info.serialization.serializer),
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

object JsonType3Serializer: KSerializer<JsonType3> {
    val multi = ArraySerializer(JsonType2.serializer())
    val single = JsonType2.serializer()
    @OptIn(InternalSerializationApi::class)
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("JsonType3", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: JsonType3) {
        if(value.nullable) encoder.encodeSerializableValue(multi, arrayOf(value.inner, JsonType2.NULL))
        else encoder.encodeSerializableValue(single, value.inner)
    }

    override fun deserialize(decoder: Decoder): JsonType3 {
        (decoder as? JsonDecoder)?.let { input ->
            val element = input.decodeJsonElement()
            return if(element is JsonArray) JsonType3(decoder.json.decodeFromJsonElement(single, element[0]), true)
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

fun Json.schemaDefinitions(types: Iterable<KSerializer<*>>): Map<String, JsonSchemaType> {
    val b = JsonSchemaBuilder(this)
    for (it in types) b.get(it)
    return b.definitions
}
fun Json.schema(type: KSerializer<*>): JsonSchemaDefinition {
    val b = JsonSchemaBuilder(this)
    b.get(type)
    return JsonSchemaDefinition(
        schema = "https://json-schema.org/draft/2019-09/schema",
        definitions = b.definitions,
        ref = "#/definitions/${b.key(type)}"
    )
}

class JsonSchemaBuilder(val json: Json, val refString: String = "#/definitions/", val useNullableProperty: Boolean = false) {
    val definitions = mutableMapOf<String, JsonSchemaType>()
    val defining = mutableSetOf<String>()
    val overrides = mutableMapOf<KClass<out KSerializer<*>>, (KSerializer<*>) -> JsonSchemaType>()
    val annotationHandlers = mutableMapOf<KClass<*>, (JsonSchemaType, Annotation) -> JsonSchemaType>()

    init {
        annotation { it: Description -> copy(description = it.text) }
        annotation { it: IntegerRange -> copy(minimum = it.min.toDouble(), maximum = it.max.toDouble()) }
        annotation { it: FloatRange -> copy(minimum = it.min, maximum = it.max) }
        annotation { it: ExpectedPattern -> copy(pattern = it.pattern) }
        annotation { it: JsonSchemaFormat -> copy(format = it.format) }
        annotation { it: DisplayName -> copy(title = it.text) }
        annotation { it: References -> copy(references = key(json.serializersModule.serializer(it.references.java))) }
        annotation { it: MultipleReferences -> copy(items = items!!.copy(references = key(json.serializersModule.serializer(it.references.java)))) }
        annotation { it: MimeType -> copy(mimeType = it.mime) }
        override { it: ServerFileSerialization ->
            JsonSchemaType(type = JsonType3(JsonType2.STRING), format = "file", options = buildJsonObject {
                putJsonObject("upload") {
                    put("upload_handler", "mainUploadHandler")
                    put("auto_upload", true)
                }
            }, links = listOf(JsonSchemaTypeLink("{{self}}", "View File")))
        }
        override { it: ExternalServerFileSerializer ->
            JsonSchemaType(type = JsonType3(JsonType2.STRING), format = "file", options = buildJsonObject {
                putJsonObject("upload") {
                    put("upload_handler", "mainUploadHandler")
                    put("auto_upload", true)
                }
            }, links = listOf(JsonSchemaTypeLink("{{self}}", "View File")))
        }
        override { it: LocalDateSerializer -> JsonSchemaType(type = JsonType3(JsonType2.STRING), format = "date") }
        override { it: LocalTimeSerializer -> JsonSchemaType(type = JsonType3(JsonType2.STRING), format = "time") }
        override { it: ZonedDateTimeSerializer -> JsonSchemaType(type = JsonType3(JsonType2.STRING), format = "date-time-zone") }
        override { it: InstantSerializer -> JsonSchemaType(type = JsonType3(JsonType2.STRING), format = "date-time") }
        override { it: ConditionSerializer<*> ->
            val desc = it.descriptor
            JsonSchemaType(
                title = desc.serialName.substringBefore('<').substringAfterLast('.').humanize(),
                type = JsonType3(JsonType2.OBJECT),
                oneOf = it.serializerMap.map { (key, ser) ->
                    val propTitle = key.humanize()
                    val value = get(ser, listOf(), propTitle)
                    JsonSchemaType(
                        title = propTitle,
                        type = JsonType3(JsonType2.OBJECT),
                        properties = mapOf(key to value)
                    )
                }
            )
        }
        override { it: ModificationSerializer<*> ->
            val desc = it.descriptor
            JsonSchemaType(
                title = desc.serialName.substringBefore('<').substringAfterLast('.').humanize(),
                type = JsonType3(JsonType2.OBJECT),
                oneOf = it.serializerMap.map { (key, ser) ->
                    val propTitle = key.humanize()
                    val value = get(ser, listOf(), propTitle)
                    JsonSchemaType(
                        title = propTitle,
                        type = JsonType3(JsonType2.OBJECT),
                        properties = mapOf(key to value)
                    )
                }
            )
        }
    }

    inline fun <reified T : Annotation> annotation(crossinline handler: JsonSchemaType.(T) -> JsonSchemaType) {
        annotationHandlers[T::class] = { a, b -> a.handler(b as T) }
    }

    inline fun <reified T : KSerializer<*>> override(crossinline handler: (T) -> JsonSchemaType) {
        overrides[T::class] = { handler(it as T) }
    }

    fun key(serializer: KSerializer<*>): String = serializer.descriptor.serialName.replace("<", "_").replace(", ", "_").replace(">", "").replace("?", "_n")

    @OptIn(InternalSerializationApi::class)
    operator fun get(serializer: KSerializer<*>, annotationsToApply: List<Annotation> = listOf(), title: String = "Value"): JsonSchemaType {
        val desc = serializer.descriptor
        val annos = annotationsToApply + desc.annotations
        if(serializer is WrappingSerializer<*, *>) {
            return get(serializer.to, annotationsToApply, title)
        }
        if (desc.kind == SerialKind.CONTEXTUAL) {
            val c2 = if (desc is LazyRenamedSerialDescriptor) desc.getter() else desc
            val contextual = c2.capturedKClass?.let { klass -> json.serializersModule.getContextual(klass) }
                ?: throw IllegalStateException("Contextual missing for $this")
            return if (desc.isNullable)
                get(contextual.nullable, annotationsToApply = annos, title = title)
            else
                get(contextual, annotationsToApply = annos, title = title)
        }
        if (desc.isNullable) {
            val inner = get(serializer.nullElement()!!, annos, title)
            if(useNullableProperty) {
                return inner.copy(nullable = true)
            } else {
                if(inner.type?.inner?.isPrimitive == true) {
                    return inner.copy(type = inner.type.copy(nullable = true))
                }
                return JsonSchemaType(oneOf = listOf(inner.copy(title = title), JsonSchemaType(type = JsonType3(JsonType2.NULL), title = "$title N/A")))
            }
        }

        fun defining(serializer: KSerializer<*>, action: ()->JsonSchemaType): JsonSchemaType {
            val key = key(serializer)
            if(defining.add(key)) {
                definitions[key] = action()
            }
            return JsonSchemaType(ref = refString(serializer))
        }

        overrides[serializer::class]?.let {
            return defining(serializer) { it(serializer).applyAnnotations(annos) }
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

            SerialKind.ENUM -> defining(serializer) { JsonSchemaType(
                type = JsonType3(JsonType2.STRING),
                oneOf = (0 until desc.elementsCount)
                    .map {
                        val value = desc.getElementName(it)
                        JsonSchemaType(
                            title = desc.getElementAnnotations(it).filterIsInstance<DisplayName>().firstOrNull()?.text
                                ?: value.humanize(),
                            const = value
                        )
                    }
            ).applyAnnotations(annos) }

            StructureKind.LIST -> JsonSchemaType(type = JsonType3(JsonType2.ARRAY), items = get(serializer.listElement() ?: throw IllegalStateException("Could not find list element for ${serializer}"), title = title)).applyAnnotations(annos)

            StructureKind.MAP -> JsonSchemaType(
                type = JsonType3(JsonType2.OBJECT),
                additionalProperties = get(serializer.mapValueElement()!!, title = title)
            ).applyAnnotations(annos)

            StructureKind.CLASS -> defining(serializer) {
                val childSerializers = (serializer as GeneratedSerializer<*>).childSerializers()
                JsonSchemaType(
                    title = desc.serialName.substringBefore('<').substringAfterLast('.').humanize(),
                    type = JsonType3(JsonType2.OBJECT),
                    properties = (0 until desc.elementsCount).associate {
                        val propTitle = desc.getElementName(it).humanize()
                        desc.getElementName(it) to get(childSerializers[it], desc.getElementAnnotations(it), propTitle).copy(
                            title = propTitle
                        )
                    }
                ).applyAnnotations(annos)
            }

            StructureKind.OBJECT -> JsonSchemaType(
                type = JsonType3(JsonType2.OBJECT),
                properties = mapOf()
            ).applyAnnotations(annos)

            PolymorphicKind.SEALED -> TODO()/*JsonSchemaType(
                type = JsonType3(JsonType2.OBJECT),
                properties = mapOf(
                    "type" to JsonSchemaType(
                        type = JsonType3(JsonType2.STRING),
                        enum = desc.elementNames.toList(),
                        anyOf =
                    )
                )
            )*/
            PolymorphicKind.OPEN -> TODO()
            SerialKind.CONTEXTUAL -> throw Error("This should not be reachable")
        }
    }

    private fun JsonSchemaType.applyAnnotations(annotations: List<Annotation>): JsonSchemaType {
        var current = this
        for (anno in annotations) {
            for(entry in annotationHandlers) {
                if(entry.key.isInstance(anno)) {
                    current = entry.value(current, anno)
                }
            }
        }
        return current
    }

    fun refString(serializer: KSerializer<*>): String = refString + key(serializer)
}