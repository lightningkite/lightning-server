@file:OptIn(ExperimentalSerializationApi::class)
package com.lightningkite.lightningdb

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialKind
import kotlin.reflect.KClass
import kotlin.time.ExperimentalTime

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class CheckReturnValue

/**
 * Which fields are text searched in the admin
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class AdminSearchFields(val fields: Array<String>)

/**
 * Which fields are columns in the admin
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class AdminTableColumns(val fields: Array<String>)

/**
 * Which fields are used to create a title in the admin
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class AdminTitleFields(val fields: Array<String>)

/**
 * Hide this field in admin forms.
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class AdminHidden()

/**
 * Hide this field in admin forms.
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class AdminViewOnly()

/**
 * Multiline widget in admin.
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class Multiline()

/**
 * Set widget in admin using RJSF.
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class UiWidget(val type: String)


/**
 * Format, passed onto schema
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class JsonSchemaFormat(val format: String)

/**
 * Minimum and Maximum values using whole numbers
 *
 * Only works when [SerialKind] is any of
 * [PrimitiveKind.BYTE], [PrimitiveKind.SHORT], [PrimitiveKind.INT], [PrimitiveKind.LONG]
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class IntegerRange(val min: Long, val max: Long)

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
annotation class ExpectedPattern(val pattern: String)

/**
 * A display name of the item in question.
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class DisplayName(val text: String)

/**
 * Hint for what should be placed here
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class Hint(val text: String)

/**
 * Which mime types are valid
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class MimeType(vararg val types: String, val maxSize: Long = Long.MAX_VALUE)

@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class MaxLength(val size: Int)

@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class MaxSize(val size: Int)

@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class Denormalized(val calculationId: String = "")

/**
 * A description of the item in question.
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class Description(val text: String)

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
@Deprecated("Use GenerateDataClassPaths insteated", ReplaceWith("GenerateDataClassPaths"))
annotation class DatabaseModel

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class GenerateDataClassPaths


@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class References(
    val references: KClass<*>,
    val reverseName: String = ""
)


@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class MultipleReferences(
    val references: KClass<*>,
    val reverseName: String = ""
)

@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class DoesNotNeedLabel

@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class Group(val name: String)

/**
 * [text] should be formatted as "_ in stock"
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class Sentence(val text: String)

/**
 * An integer indicating how important this is.
 * Corresponds to header sizes.
 * 1-6 represent H1-H6
 * 7 represents text
 * 8 represents subtext
 */
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class Importance(val size: Int)


@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class Index


@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class Unique


@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class TextIndex(val fields: Array<String>)


@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
@Repeatable
annotation class IndexSet(val fields: Array<String>)


@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
@Repeatable
annotation class UniqueSet(val fields: Array<String>)


@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class NamedIndex(val indexName: String)


@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class NamedUnique(val indexName: String)


@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
@Repeatable
annotation class NamedIndexSet(val fields: Array<String>, val indexName: String)


@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
@Repeatable
annotation class NamedUniqueSet(val fields: Array<String>, val indexName: String)


@RequiresOptIn(level = RequiresOptIn.Level.WARNING)
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.ANNOTATION_CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.TYPEALIAS
)
public annotation class ExperimentalLightningServer(val explanation: String)
