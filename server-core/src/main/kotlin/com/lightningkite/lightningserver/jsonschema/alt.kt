package com.lightningkite.lightningserver.jsonschema

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.db.ModelRestEndpoints
import com.lightningkite.lightningserver.files.ExternalServerFileSerializer
import com.lightningkite.lightningserver.files.UploadEarlyEndpoint
import com.lightningkite.lightningserver.routes.fullUrl
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.typed.Documentable
import com.lightningkite.lightningserver.typed.docGroup
import io.ktor.util.reflect.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.*
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
enum class JsonType2 {
    @SerialName("null")
    NULL,

    @SerialName("array")
    ARRAY,

    @SerialName("number")
    NUMBER,

    @SerialName("integer")
    INTEGER,

    @SerialName("string")
    STRING,

    @SerialName("boolean")
    BOOLEAN,

    @SerialName("object")
    OBJECT,
}

@Serializable
data class JsonSchemaType(
    @SerialName("\$ref") val ref: String? = null,
    val title: String? = null,
    val references: String? = null,
    val description: String? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val mimeType: String? = null,
    val type: JsonType2? = null,
    val pattern: String? = null,
    val options: JsonObject? = null,
    val format: String? = null,
    val items: JsonSchemaType? = null,
    val properties: Map<String, JsonSchemaType>? = null,
    val additionalProperties: JsonSchemaType? = null,
    val required: List<String>? = null,
    val anyOf: List<JsonSchemaType>? = null,
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

private val camelRegex = "[a-z][A-Z]".toRegex()
private val snakeRegex = "_[a-zA-Z]".toRegex()
fun String.humanize(): String = camelRegex.replace(this) {
    "${it.value[0]} ${it.value[1].uppercase()}"
}.let {
    snakeRegex.replace(it) {
        " " + it.value[1].uppercase()
    }
}.replaceFirstChar { it.uppercaseChar() }.trim()
fun String.kabobCase(): String = camelRegex.replace(this) {
    "${it.value[0]}-${it.value[1]}"
}.let {
    snakeRegex.replace(it) {
        "-" + it.value[1]
    }
}.lowercase().trim()

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

class JsonSchemaBuilder(val json: Json) {
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
        annotation { it: MimeType -> copy(mimeType = it.mime) }
        override { it: ServerFileSerialization ->
            JsonSchemaType(type = (JsonType2.STRING), format = "file", options = buildJsonObject {
                putJsonObject("upload") {
                    put("upload_handler", "mainUploadHandler")
                    put("auto_upload", true)
                }
            }, links = listOf(JsonSchemaTypeLink("{{self}}", "View File")))
        }
        override { it: ExternalServerFileSerializer ->
            JsonSchemaType(type = (JsonType2.STRING), format = "file", options = buildJsonObject {
                putJsonObject("upload") {
                    put("upload_handler", "mainUploadHandler")
                    put("auto_upload", true)
                }
            }, links = listOf(JsonSchemaTypeLink("{{self}}", "View File")))
        }
        override { it: LocalDateSerializer -> JsonSchemaType(type = (JsonType2.STRING), format = "date") }
        override { it: LocalTimeSerializer -> JsonSchemaType(type = (JsonType2.STRING), format = "time") }
        override { it: ZonedDateTimeSerializer -> JsonSchemaType(type = (JsonType2.STRING), format = "date-time-zone") }
        override { it: InstantSerializer -> JsonSchemaType(type = (JsonType2.STRING), format = "date-time") }
        override { it: ConditionSerializer<*> -> JsonSchemaType(type = (JsonType2.OBJECT)) }
        override { it: ModificationSerializer<*> -> JsonSchemaType(type = (JsonType2.OBJECT)) }
    }

    inline fun <reified T : Annotation> annotation(crossinline handler: JsonSchemaType.(T) -> JsonSchemaType) {
        annotationHandlers[T::class] = { a, b -> a.handler(b as T) }
    }

    inline fun <reified T : KSerializer<*>> override(crossinline handler: (T) -> JsonSchemaType) {
        overrides[T::class] = { handler(it as T) }
    }

    fun key(serializer: KSerializer<*>): String = serializer.descriptor.serialName

    @OptIn(InternalSerializationApi::class)
    operator fun get(serializer: KSerializer<*>, annotationsToApply: List<Annotation> = listOf()): JsonSchemaType {
        val desc = serializer.descriptor
        val annos = annotationsToApply + desc.annotations
        if (desc.kind == SerialKind.CONTEXTUAL) {
            val c2 = if (desc is LazyRenamedSerialDescriptor) desc.getter() else desc
            val contextual = c2.capturedKClass?.let { klass -> json.serializersModule.getContextual(klass) }
                ?: throw IllegalStateException("Contextual missing for $this")
            return if (desc.isNullable)
                get(contextual.nullable)
            else
                get(contextual)
        }
        if (desc.isNullable) {
            return JsonSchemaType(anyOf = listOf(get(serializer.nullElement()!!, annos).copy(title = "Present"), JsonSchemaType(type = (JsonType2.NULL), title = "N/A")))
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
            PrimitiveKind.BOOLEAN -> JsonSchemaType(type = (JsonType2.BOOLEAN)).applyAnnotations(annos)
            PrimitiveKind.BYTE,
            PrimitiveKind.SHORT,
            PrimitiveKind.LONG,
            PrimitiveKind.INT -> JsonSchemaType(type = (JsonType2.INTEGER)).applyAnnotations(annos)
            PrimitiveKind.FLOAT,
            PrimitiveKind.DOUBLE,
            -> JsonSchemaType(type = (JsonType2.NUMBER)).applyAnnotations(annos)

            PrimitiveKind.CHAR,
            PrimitiveKind.STRING,
            -> JsonSchemaType(type = (JsonType2.STRING)).applyAnnotations(annos)

            SerialKind.ENUM -> defining(serializer) { JsonSchemaType(
                type = (JsonType2.STRING),
                anyOf = (0 until desc.elementsCount)
                    .map {
                        val value = desc.getElementName(it)
                        JsonSchemaType(
                            title = desc.getElementAnnotations(it).filterIsInstance<DisplayName>().firstOrNull()?.text
                                ?: value.humanize(),
                            const = value
                        )
                    }
            ).applyAnnotations(annos) }

            StructureKind.LIST -> JsonSchemaType(type = (JsonType2.ARRAY), items = get(serializer.listElement()!!)).applyAnnotations(annos)

            StructureKind.MAP -> JsonSchemaType(
                type = (JsonType2.OBJECT),
                additionalProperties = get(serializer.mapValueElement()!!)
            ).applyAnnotations(annos)

            StructureKind.CLASS -> defining(serializer) {
                val childSerializers = (serializer as GeneratedSerializer<*>).childSerializers()
                JsonSchemaType(
                    title = desc.serialName.substringBefore('<').substringAfterLast('.').humanize(),
                    type = (JsonType2.OBJECT),
                    properties = (0 until desc.elementsCount).associate {
                        desc.getElementName(it) to get(childSerializers[it]).copy(
                            title = desc.getElementName(it).humanize()
                        ).applyAnnotations(desc.getElementAnnotations(it))
                    }
                ).applyAnnotations(annos)
            }

            StructureKind.OBJECT -> JsonSchemaType(
                type = (JsonType2.OBJECT),
                properties = mapOf()
            ).applyAnnotations(annos)

            PolymorphicKind.SEALED -> TODO()/*JsonSchemaType(
                type = (JsonType2.OBJECT),
                properties = mapOf(
                    "type" to JsonSchemaType(
                        type = (JsonType2.STRING),
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

    fun refString(serializer: KSerializer<*>): String = "#/definitions/${key(serializer)}"
}