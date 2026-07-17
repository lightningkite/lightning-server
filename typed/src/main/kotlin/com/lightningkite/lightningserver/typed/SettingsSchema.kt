package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.typed.jsonschema.JsonSchemaType
import com.lightningkite.lightningserver.typed.jsonschema.schema
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.modules.SerializersModule

private val logger = KotlinLogging.logger("com.lightningkite.lightningserver.typed.SettingsSchema")

/**
 * Generates a JSON Schema (draft 2019-09) describing a server's `settings.json` file.
 *
 * Editors and CI can use the schema to validate `settings.json`, catching typo'd keys that the loader's
 * `ignoreUnknownKeys = true` would otherwise silently swallow.
 *
 * The root object is hand-built: it has one property per [ServerSetting.name], marks non-optional settings as
 * `required`, sets `additionalProperties: false` so unexpected keys are flagged, and permits the optional `defaults`
 * string key used by the settings loader. Each per-setting sub-schema is produced by reusing
 * [com.lightningkite.lightningserver.typed.jsonschema.schema]; any setting whose type cannot be schematized
 * (e.g. sealed/polymorphic settings) falls back to a permissive empty schema `{}` with a logged warning, rather than
 * failing the whole export.
 *
 * @param module The serializers module the server uses (typically the engine's `internalSerializersModule`), needed
 *               to resolve contextual serializers in setting types.
 * @return The full JSON Schema as a [JsonObject].
 */
public fun ServerBuilder.settingsSchemaJson(module: SerializersModule): JsonObject {
    val json = Json {
        serializersModule = module
        prettyPrint = true
        encodeDefaults = true
    }
    val settings: List<ServerSetting<*, *>> = build().settings.distinctBy { it.name }.sortedBy { it.name }

    val definitions = LinkedHashMap<String, JsonSchemaType>()
    val propertySchemas = LinkedHashMap<String, JsonObject>()
    val required = ArrayList<String>()

    for (setting in settings) {
        if (!setting.optional) required.add(setting.name)
        val schemaObject: JsonObject = try {
            val def = json.schema(setting.serializer)
            // Collect the definitions this setting introduced so refs resolve against the shared map.
            def.definitions.forEach { (k, v) -> definitions.putIfAbsent(k, v) }
            val refKey = def.ref?.removePrefix("#/definitions/")
            if (refKey != null && def.definitions.containsKey(refKey)) {
                // Structured type: reference the shared definition.
                buildJsonObject { put("\$ref", def.ref!!) }
            } else {
                // Primitive settings (String, Int, ...) produce no definition; embed an inline schema so the ref is not dangling.
                buildJsonObject { put("type", primitiveJsonType(setting)) }
            }
        } catch (e: Exception) {
            // Sealed/polymorphic and other un-schematizable settings: stay permissive instead of failing the export.
            logger.warn { "Could not generate JSON schema for setting '${setting.name}' (${setting.serializer.descriptor.serialName}); using permissive {}: ${e.message}" }
            JsonObject(emptyMap())
        }
        propertySchemas[setting.name] = schemaObject
    }

    val definitionsJson = JsonObject(definitions.mapValues { json.encodeToJsonElement(JsonSchemaType.serializer(), it.value) })

    return buildJsonObject {
        put("\$schema", "https://json-schema.org/draft/2019-09/schema")
        put("type", "object")
        putJsonObject("properties") {
            for ((name, schema) in propertySchemas) put(name, schema)
            // The settings loader recognizes an optional top-level "defaults" string key.
            putJsonObject("defaults") { put("type", "string") }
        }
        put("required", buildJsonArray { required.sorted().forEach { add(JsonPrimitive(it)) } })
        put("additionalProperties", false)
        if (definitionsJson.isNotEmpty()) put("definitions", definitionsJson)
    }
}

/** Maps a primitive setting's serializer kind to the corresponding JSON Schema `type` string. */
private fun primitiveJsonType(setting: ServerSetting<*, *>): String =
    when (setting.serializer.descriptor.kind) {
        PrimitiveKind.BOOLEAN -> "boolean"
        PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> "integer"
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> "number"
        PrimitiveKind.CHAR, PrimitiveKind.STRING -> "string"
        SerialKind.ENUM -> "string"
        else -> "string"
    }
