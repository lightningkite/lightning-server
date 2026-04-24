package com.lightningkite.lightningserver.serialization

import com.lightningkite.services.data.StringArrayFormat
import com.lightningkite.services.serializers.KotlinBytesFormat
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

    /**
     * JSON format that omits properties with default values.
     *
     * Useful for reducing payload size when default values can be inferred on the receiving end.
     * Otherwise identical to [json].
     */
    public open val jsonWithoutExplicitNulls: Json = Json {
        this.serializersModule = this@Serialization.serializersModule
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true // by Claude - required so fields like jsonrpc="2.0" are included
        explicitNulls = false
    }
}

/*
 * TODO: API Recommendations for Serialization.kt
 *
 * 1. The class is open and all properties are open, allowing subclasses to override formats.
 *    However, there's no clear use case documented for when/why you'd subclass this.
 *    Consider making it final or documenting the extension points.
 *
 * 2. Both JSON configurations use isLenient=true which accepts non-standard JSON.
 *    This could allow malformed input to pass through. Document the security implications
 *    or consider making this configurable.
 *
 * 3. No validation limits on the formats (max nesting depth, max string length, etc.).
 *    Large or deeply nested JSON could cause DoS. Consider adding configurable limits.
 *
 * 4. The serializersModule defaults to empty, which means no polymorphic serialization
 *    or contextual serializers by default. This could be surprising. Document clearly.
 *
 * 5. No prettyPrint option exposed for JSON. While prettyPrint=false is usually right
 *    for production, having a debug mode could be useful. Consider adding a companion
 *    object factory method like Serialization.debug().
 */