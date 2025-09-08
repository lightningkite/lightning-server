package com.lightningkite.lightningserver.typed.jsonschema

import com.lightningkite.lightningserver.typed.sdk.titleCase
import com.lightningkite.services.data.*
import com.lightningkite.services.database.ConditionSerializer
import com.lightningkite.services.database.KSerializerKey
import com.lightningkite.services.database.ModificationSerializer
import com.lightningkite.services.database.WrappingSerializer
import com.lightningkite.services.database.childSerializersOrNull
import com.lightningkite.services.database.innerElement
import com.lightningkite.services.database.innerElement2
import com.lightningkite.services.database.nullElement
import com.lightningkite.services.database.serializableProperties
import kotlinx.serialization.*
import kotlinx.serialization.builtins.ArraySerializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import kotlin.collections.Iterable
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.associate
import kotlin.collections.filterIsInstance
import kotlin.collections.firstOrNull
import kotlin.collections.iterator
import kotlin.collections.listOf
import kotlin.collections.map
import kotlin.collections.mapOf
import kotlin.collections.mutableMapOf
import kotlin.collections.mutableSetOf
import kotlin.collections.plus
import kotlin.collections.set
import kotlin.reflect.KClass

internal class JsonSchemaException(message: String? = null, cause: Throwable? = null): Exception(message, cause)


@Serializable
public enum class JavascriptCoreType(public val isPrimitive: Boolean) {
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

@Serializable(JavascriptCoreTypeWithNullabilitySerializer::class)
public data class JavascriptCoreTypeWithNullability(val inner: JavascriptCoreType, val nullable: Boolean = false)

internal object JavascriptCoreTypeWithNullabilitySerializer : KSerializer<JavascriptCoreTypeWithNullability> {
    @OptIn(ExperimentalSerializationApi::class)
    val multi = ArraySerializer(JavascriptCoreType.serializer())
    val single = JavascriptCoreType.serializer()

    @OptIn(InternalSerializationApi::class)
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.lightningkite.lightningserver.jsonschema.JsonType3", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: JavascriptCoreTypeWithNullability) {
        if (value.nullable) encoder.encodeSerializableValue(multi, arrayOf(value.inner, JavascriptCoreType.NULL))
        else encoder.encodeSerializableValue(single, value.inner)
    }

    override fun deserialize(decoder: Decoder): JavascriptCoreTypeWithNullability {
        (decoder as? JsonDecoder)?.let { input ->
            val element = input.decodeJsonElement()
            return if (element is JsonArray) JavascriptCoreTypeWithNullability(decoder.json.decodeFromJsonElement(single, element[0]), true)
            else JavascriptCoreTypeWithNullability(decoder.json.decodeFromJsonElement(single, element))
        }
        return JavascriptCoreTypeWithNullability(JavascriptCoreType.serializer().deserialize(decoder))
    }
}

@Serializable
public data class JsonSchemaType(
    @SerialName("\$ref") val ref: String? = null,
    val title: String? = null,
    val nullable: Boolean? = null,
    val references: String? = null,
    val description: String? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val mimeType: String? = null,
    val type: JavascriptCoreTypeWithNullability? = null,
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
public data class JsonSchemaDefinition(
    @SerialName("\$schema") val schema: String,
    val definitions: Map<String, JsonSchemaType> = mapOf(),
    @SerialName("\$ref") val ref: String? = null,
)

@Serializable
public data class JsonSchemaTypeLink(
    val href: String,
    val rel: String,
)

internal fun Json.schemaDefinitions(types: Iterable<KSerializer<*>>): Map<String, JsonSchemaType> {
    val b = JsonSchemaBuilder(this)
    for (it in types) b.get(it)
    return b.definitions
}

internal fun Json.schema(type: KSerializer<*>): JsonSchemaDefinition {
    val b = JsonSchemaBuilder(this)
    b.get(type)
    return JsonSchemaDefinition(
        schema = "https://json-schema.org/draft/2019-09/schema",
        definitions = b.definitions,
        ref = "#/definitions/${b.key(type)}"
    )
}

internal class JsonSchemaBuilder(
    val json: Json,
    val refString: String = "#/definitions/",
    val useNullableProperty: Boolean = false
) {
    val definitions = mutableMapOf<String, JsonSchemaType>()
    val defining = mutableSetOf<String>()
    val overrides = mutableMapOf<String, (KSerializer<*>) -> JsonSchemaType>()
    val annotationHandlers = mutableMapOf<KClass<*>, (JsonSchemaType, Annotation) -> JsonSchemaType>()

    init {
        annotation { it: Description -> copy(description = it.text) }
        annotation { it: IntegerRange -> copy(minimum = it.min.toDouble(), maximum = it.max.toDouble()) }
        annotation { it: FloatRange -> copy(minimum = it.min, maximum = it.max) }
        annotation { it: ExpectedPattern -> copy(pattern = it.pattern) }
        annotation { it: JsonSchemaFormat -> copy(format = it.format) }
        annotation { it: DisplayName -> copy(title = it.text) }
        annotation { _: AdminHidden -> copy(uiWidget = "hidden") }
        annotation { _: Multiline -> copy(uiWidget = "textarea") }
        annotation { it: UiWidget -> copy(uiWidget = it.type) }
        annotation { it: References -> copy(references = key(json.serializersModule.serializer(it.references.java))) }
        annotation { it: MultipleReferences ->
            try {
                copy(
                    items = items!!.copy(
                        references = key(
                            json.serializersModule.serializer(
                                it.references.java
                            )
                        )
                    )
                )
            } catch (e: Exception){
                throw JsonSchemaException("Failed to handle MultipleReferences annotation", e)
            }
        }
        annotation { it: MimeType -> copy(mimeType = it.types.joinToString(", ")) }
        override("com.lightningkite.lightningserver.files.ServerFile") {
            JsonSchemaType(
                title = "Server File",
                type = JavascriptCoreTypeWithNullability(JavascriptCoreType.STRING),
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
        override("com.lightningkite.UUID") {
            JsonSchemaType(
                title = "UUID",
                type = JavascriptCoreTypeWithNullability(JavascriptCoreType.STRING),
                format = "uuid",
                pattern = "^[0-9a-fA-F]{8}\\b-[0-9a-fA-F]{4}\\b-[0-9a-fA-F]{4}\\b-[0-9a-fA-F]{4}\\b-[0-9a-fA-F]{12}\$"
            )
        }
        override("kotlinx.datetime.LocalDate") {
            JsonSchemaType(
                title = "Local Date",
                type = JavascriptCoreTypeWithNullability(JavascriptCoreType.STRING),
                format = "date",
                pattern = "^\\d\\d\\d\\d-\\d\\d-\\d\\d$"
            )
        }
        override("kotlinx.datetime.LocalTime") {
            JsonSchemaType(
                title = "Local Time",
                type = JavascriptCoreTypeWithNullability(JavascriptCoreType.STRING),
                format = "time",
                pattern = "^\\d\\d:\\d\\d(:\\d\\d(\\.\\d+)?)?$"
            )
        }
        override("kotlinx.datetime.ZonedDateTime") {
            JsonSchemaType(
                title = "Zoned Date Time",
                type = JavascriptCoreTypeWithNullability(JavascriptCoreType.STRING),
                format = "date-time-zone",
                pattern = "^\\d\\d\\d\\d-\\d\\d-\\d\\dT\\d\\d:\\d\\d(:\\d\\d(\\.\\d+)?)?(([+-]\\d\\d:\\d\\d(\\[[^\\]+]\\])?)|Z)$"
            )
        }
        override("kotlinx.datetime.Instant") {
            JsonSchemaType(
                title = "Instant",
                type = JavascriptCoreTypeWithNullability(JavascriptCoreType.STRING),
                format = "date-time",
                pattern = "^\\d\\d\\d\\d-\\d\\d-\\d\\dT\\d\\d:\\d\\d(:\\d\\d(\\.\\d+)?)?Z$"
            )
        }
        override("com.lightningkite.lightningdb.Condition") {
            val subtype = (it as ConditionSerializer<*>).inner
            JsonSchemaType(
                title = "Condition for ${subtype.descriptor.serialName.substringAfterLast('.')}",
                type = JavascriptCoreTypeWithNullability(JavascriptCoreType.OBJECT),
                oneOf = it.options.map {
                    JsonSchemaType(
                        title = it.serializer.descriptor.serialName,
                        type = JavascriptCoreTypeWithNullability(JavascriptCoreType.OBJECT),
                        properties = mapOf(it.serializer.descriptor.serialName to get(it.serializer, direct = true))
                    )
                }
            )
        }
        override("com.lightningkite.lightningdb.Modification") {
            val subtype = (it as ModificationSerializer<*>).inner
            JsonSchemaType(
                title = "Modification for ${subtype}",
                type = JavascriptCoreTypeWithNullability(JavascriptCoreType.OBJECT),
                oneOf = it.options.map {
                    JsonSchemaType(
                        title = it.serializer.descriptor.serialName,
                        type = JavascriptCoreTypeWithNullability(JavascriptCoreType.OBJECT),
                        properties = mapOf(it.serializer.descriptor.serialName to get(it.serializer, direct = true))
                    )
                }
            )
        }
    }

    inline fun <reified T : Annotation> annotation(crossinline handler: JsonSchemaType.(T) -> JsonSchemaType) {
        annotationHandlers[T::class] = { a, b -> a.handler(b as T) }
    }

    inline fun override(key: String, crossinline handler: (KSerializer<*>) -> JsonSchemaType) {
        overrides[key] = { handler(it) }
    }

    val existingKeys1 = HashMap<KSerializerKey, String>()
    val existingKeys2 = HashMap<String, KSerializerKey>()
    fun key(serializer: KSerializer<*>): String {
        val key = KSerializerKey(serializer)
        existingKeys1[key]?.let { return it }
        val baseName = serializer.descriptor.serialName.replace('/', '_')
        var index = 0
        while (true) {
            val name = baseName + (if (index == 0) "" else index.toString())
            if (!existingKeys2.containsKey(name)) {
                existingKeys1[key] = name
                existingKeys2[name] = key
                return name
            }
            index++
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    operator fun get(
        serializer: KSerializer<*>,
        annotationsToApply: List<Annotation> = listOf(),
        title: String = "Value",
        direct: Boolean = false,
    ): JsonSchemaType {
        try {
            val annos = annotationsToApply + serializer.descriptor.annotations
            if (serializer.descriptor.isNullable) {
                val inner = get(serializer.nullElement()!!, annos, title)
                if (useNullableProperty) {
                    return inner.copy(nullable = true)
                } else {
//                    if (inner.type?.inner?.isPrimitive == true) {
//                        return inner.copy(type = inner.type.copy(nullable = true))
//                    }
                    return JsonSchemaType(
                        oneOf = listOf(
                            inner.copy(title = title),
                            JsonSchemaType(type = JavascriptCoreTypeWithNullability(JavascriptCoreType.NULL), title = "$title N/A")
                        )
                    )
                }
            }

            fun defining(serializer: KSerializer<*>, action: () -> JsonSchemaType): JsonSchemaType {
                if (direct) return action()
                val key = key(serializer)
                if (defining.add(key)) {
                    if (serializer.descriptor.serialName == "Not") throw JsonSchemaException()
                    definitions[key] = action()
                }
                return JsonSchemaType(ref = refString(serializer))
            }
            if (serializer is WrappingSerializer<*, *>) {
                return get(serializer.to, title = title, annotationsToApply = annotationsToApply)
            }

            val ser = serializer.unwrap()
            overrides[ser.descriptor.serialName.substringBefore('/').substringBefore('<')]?.let {
                return defining(ser) { it(ser) }.applyAnnotations(annos)
            }
            return when (ser.descriptor.kind) {
                PrimitiveKind.BOOLEAN -> JsonSchemaType(type = JavascriptCoreTypeWithNullability(JavascriptCoreType.BOOLEAN)).applyAnnotations(annos)
                PrimitiveKind.BYTE,
                PrimitiveKind.SHORT,
                PrimitiveKind.LONG,
                PrimitiveKind.INT -> JsonSchemaType(type = JavascriptCoreTypeWithNullability(JavascriptCoreType.INTEGER)).applyAnnotations(annos)

                PrimitiveKind.FLOAT,
                PrimitiveKind.DOUBLE,
                -> JsonSchemaType(type = JavascriptCoreTypeWithNullability(JavascriptCoreType.NUMBER)).applyAnnotations(annos)

                PrimitiveKind.CHAR,
                PrimitiveKind.STRING,
                -> JsonSchemaType(type = JavascriptCoreTypeWithNullability(JavascriptCoreType.STRING)).applyAnnotations(annos)

                SerialKind.ENUM -> defining(serializer) {
                    JsonSchemaType(
                        title = ser.descriptor.serialName.substringBefore('/').substringBefore('<').substringAfterLast('.').titleCase(),
                        type = JavascriptCoreTypeWithNullability(JavascriptCoreType.STRING),
                        oneOf = (0 until ser.descriptor.elementsCount)
                            .map {
                                val value = ser.descriptor.getElementName(it)
                                JsonSchemaType(
                                    title = ser.descriptor.getElementAnnotations(it).filterIsInstance<DisplayName>()
                                        .firstOrNull()?.text
                                        ?: value.titleCase(),
                                    const = value
                                )
                            }
                    ).applyAnnotations(annos)
                }

                StructureKind.LIST -> JsonSchemaType(
                    type = JavascriptCoreTypeWithNullability(JavascriptCoreType.ARRAY),
                    items = get(
                        serializer.innerElement(), title = title
                    )
                ).applyAnnotations(annos)

                StructureKind.MAP -> JsonSchemaType(
                    type = JavascriptCoreTypeWithNullability(JavascriptCoreType.OBJECT),
                    additionalProperties = get(serializer.innerElement2(), title = title)
                ).applyAnnotations(annos)

                StructureKind.CLASS -> defining(serializer) {
                    JsonSchemaType(
                        title = ser.descriptor.serialName.substringBefore('/').substringBefore('<').substringAfterLast('.').titleCase(),
                        type = JavascriptCoreTypeWithNullability(JavascriptCoreType.OBJECT),
                        properties = ser.serializableProperties?.associate {
                            val propTitle = it.name.titleCase()
                            it.name to get(
                                it.serializer,
                                ser.descriptor.getElementIndex(it.name).takeUnless { it == -1 }?.let { ser.descriptor.getElementAnnotations(it) } ?: listOf(),
                                propTitle
                            ).copy(
                                title = propTitle
                            )
                        } ?: ser.childSerializersOrNull()?.withIndex()?.associate {
                            val name = ser.descriptor.getElementName(it.index)
                            val propTitle = name.titleCase()
                            name to get(
                                it.value,
                                ser.descriptor.getElementAnnotations(it.index),
                                propTitle
                            ).copy(
                                title = propTitle
                            )
                        }
                    ).applyAnnotations(annos)
                }

                StructureKind.OBJECT -> JsonSchemaType(
                    type = JavascriptCoreTypeWithNullability(JavascriptCoreType.OBJECT),
                    properties = mapOf()
                ).applyAnnotations(annos)

                PolymorphicKind.SEALED -> throw Error("Cannot generate JSON Schema for polymorphic type ${ser.descriptor.serialName}")
                PolymorphicKind.OPEN -> throw Error("Cannot generate JSON Schema for polymorphic type ${ser.descriptor.serialName}")
                SerialKind.CONTEXTUAL -> throw Error("This should not be reachable - ${ser.descriptor.serialName} could be unwrapped no further")
            }
        } catch(e: Exception) {
            throw JsonSchemaException("Failed to get schema for ${serializer.descriptor.serialName}", e)
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

    @OptIn(ExperimentalSerializationApi::class)
    @Suppress("UNCHECKED_CAST")
    fun KSerializer<*>.unwrap(): KSerializer<*> {
        return if(this.descriptor.isNullable) this.innerElement()
        else if(this.descriptor.kind == SerialKind.CONTEXTUAL) return json.serializersModule.getContextual<Any>(this.descriptor.capturedKClass as KClass<Any>) as KSerializer<*>
        else this
    }

    fun refString(serializer: KSerializer<*>): String = refString + key(serializer)
}
