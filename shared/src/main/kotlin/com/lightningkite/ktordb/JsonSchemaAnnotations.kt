package com.lightningkite.ktorbatteries.jsonschema

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialKind

@Target()
annotation class JsonSchema {
    /**
     * Format, passed onto schema
     */
    @SerialInfo
    @Retention(AnnotationRetention.BINARY)
    @Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
    annotation class Options(val json: String)

    /**
     * Format, passed onto schema
     */
    @SerialInfo
    @Retention(AnnotationRetention.BINARY)
    @Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
    annotation class Format(val format: String)

    /**
     * Description of this property
     */
    @SerialInfo
    @Repeatable
    @Retention(AnnotationRetention.BINARY)
    @Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
    annotation class Description(val lines: Array<out String>)

    /**
     * Enum-like values for non-enum string
     */
    @SerialInfo
    @Retention(AnnotationRetention.BINARY)
    @Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
    annotation class StringEnum(val values: Array<out String>)

    /**
     * Minimum and Maximum values using whole numbers
     *
     * Only works when [SerialKind] is any of
     * [PrimitiveKind.BYTE], [PrimitiveKind.SHORT], [PrimitiveKind.INT], [PrimitiveKind.LONG]
     */
    @SerialInfo
    @Retention(AnnotationRetention.BINARY)
    @Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
    annotation class IntRange(val min: Long, val max: Long)

    /**
     * Minimum and Maximum values using floating point numbers
     *
     * Only works when [SerialKind] is [PrimitiveKind.FLOAT] or [PrimitiveKind.DOUBLE]
     */
    @SerialInfo
    @Retention(AnnotationRetention.BINARY)
    @Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
    annotation class FloatRange(val min: Double, val max: Double)

    /**
     * [pattern] to use on this property
     *
     * Only works when [SerialKind] is [PrimitiveKind.STRING]
     */
    @SerialInfo
    @Retention(AnnotationRetention.BINARY)
    @Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
    annotation class Pattern(val pattern: String)

    /**
     * Should this property be a definition and be referenced using [id]?
     *
     * @param id The id for this definition, this will be referenced by '#/definitions/$[id]'
     */
    @SerialInfo
    @Retention(AnnotationRetention.BINARY)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
    annotation class Definition(val id: String)

    /**
     * This property will not create definitions
     */
    @SerialInfo
    @Retention(AnnotationRetention.BINARY)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
    annotation class NoDefinition
}
