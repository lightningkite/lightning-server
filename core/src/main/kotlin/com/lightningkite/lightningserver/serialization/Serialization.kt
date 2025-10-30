package com.lightningkite.lightningserver.serialization

import com.lightningkite.lightningserver.serialization.FormDataFormat
import com.lightningkite.services.data.KotlinBytesFormat
import com.lightningkite.services.data.StringArrayFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule

/**
 * Central configuration for all serialization formats used within Lightning Server.
 *
 * This class provides preconfigured instances of various serialization formats (JSON, form data,
 * binary formats) that share a common [SerializersModule]. The shared module ensures consistent
 * handling of polymorphic types and contextual serializers across all formats.
 *
 * @property serializersModule The KotlinX Serialization module containing custom serializers,
 *                            polymorphic mappings, and contextual serializers. Defaults to an empty module.
 *
 * @see SerializersModule
 */
public open class Serialization(public val serializersModule: SerializersModule = SerializersModule { }) {
    /**
     * Format for serializing collections to/from string arrays.
     * Used for query parameters and similar use cases where data is represented as arrays of strings.
     */
    public open val stringArrayFormat: StringArrayFormat = StringArrayFormat(serializersModule)

    /**
     * Binary format for efficient Kotlin object serialization.
     * Suitable for internal communication or caching where performance is critical.
     */
    public open val kotlinBytesFormat: KotlinBytesFormat = KotlinBytesFormat(serializersModule)

    /**
     * Format for application/x-www-form-urlencoded data.
     * Used for parsing HTML form submissions and encoding data as URL parameters.
     */
    public open val formDataFormat: FormDataFormat = FormDataFormat(serializersModule)

    /**
     * Standard JSON format with defaults encoded.
     *
     * Configuration:
     * - ignoreUnknownKeys: true - Won't fail on extra fields in JSON
     * - isLenient: true - Accepts non-standard JSON (e.g., unquoted keys)
     * - encodeDefaults: true - Includes properties with default values in output
     */
    public open val json: Json = Json {
        this.serializersModule = this@Serialization.serializersModule
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /**
     * JSON format that omits properties with default values.
     *
     * Useful for reducing payload size when default values can be inferred on the receiving end.
     * Otherwise identical to [json].
     */
    public open val jsonWithoutDefaults: Json = Json {
        this.serializersModule = this@Serialization.serializersModule
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }
}