@file:OptIn(ExperimentalSerializationApi::class)
package com.lightningkite.lightningdb

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialKind
import kotlin.reflect.KClass
import kotlin.time.ExperimentalTime

@Retention(AnnotationRetention.RUNTIME)
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
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class DisplayName(val text: String)

/**
 * A display name of the item in question.
 */
@SerialInfo
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class MimeType(vararg val types: String)

/**
 * A description of the item in question.
 */
@SerialInfo
@Retention(AnnotationRetention.RUNTIME)
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
    val references: KClass<*>
)


@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class MultipleReferences(
    val references: KClass<*>
)


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
//@Repeatable
annotation class IndexSet(val fields: Array<String>)

// Jank patch? For what and how?
// The problem this works around is that Repeatable annotations currently do not
// work correct with kotlinx serialization. Only the last instance shows up.
// To jank a patch for this, I created a second version that allows you to
// include multiple sets by adding a delimiter, ":", into the list.
// An example ["field1", "field2", ":", "field1", "field3"]
// If the kotlinx issue is resolved, then this will become deprecated, and
// repeatable added back into the normal one.
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class IndexSetJankPatch(val fields: Array<String>)


@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
//@Repeatable
annotation class UniqueSet(val fields: Array<String>)


// Jank patch? For what and how?
// The problem this works around is that Repeatable annotations currently do not
// work correct with kotlinx serialization. Only the last instance shows up.
// To jank a patch for this, I created a second version that allows you to
// include multiple sets by adding a delimiter(":") into the list.
// An example ["field1", "field2", ":", "field1", "field3"]
// If the kotlinx issue is resolved, then this will become deprecated, and
// repeatable added back into the normal one.
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class UniqueSetJankPatch(val fields: Array<String>)


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
//@Repeatable
annotation class NamedIndexSet(val fields: Array<String>, val indexName: String)


// Jank patch? For what and how?
// The problem this works around is that Repeatable annotations currently do not
// work correct with kotlinx serialization. Only the last instance shows up.
// To jank a patch for this, I created a second version that allows you to
// include multiple sets by adding a delimiter(":") into the list and names.
// An example ["field1", "field2", ":", "field1", "field3"], "Name 1:Name 2"
// If the kotlinx issue is resolved, then this will become deprecated, and
// repeatable added back into the normal one.
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class NamedIndexSetJankPatch(val fields: Array<String>, val indexNames: String)


@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
//@Repeatable
annotation class NamedUniqueSet(val fields: Array<String>, val indexName: String)


// Jank patch? For what and how?
// The problem this works around is that Repeatable annotations currently do not
// work correct with kotlinx serialization. Only the last instance shows up.
// To jank a patch for this, I created a second version that allows you to
// include multiple sets by adding a delimiter(":") into the list.
// An example ["field1", "field2", ":", "field1", "field3"], "Name 1:Name 2"
// If the kotlinx issue is resolved, then this will become deprecated, and
// repeatable added back into the normal one.
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class NamedUniqueSetJankPatch(val fields: Array<String>, val indexNames: String)

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
