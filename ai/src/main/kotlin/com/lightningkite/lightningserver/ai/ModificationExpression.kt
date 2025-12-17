package com.lightningkite.lightningserver.ai

import com.lightningkite.services.data.Description
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.SerializableProperty
import com.lightningkite.services.database.serializableProperties
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.serializer
import kotlin.jvm.JvmInline

/**
 * A value class that wraps an SQL-like expression string that can be parsed into a Modification<T>.
 *
 * This provides a simpler alternative to the generic Modification<T> type for LLM tool integration,
 * since Koog ToolDescriptor doesn't support generics.
 *
 * ## Supported Syntax
 *
 * ### Assignment Operations:
 * - `field = value` - Assign a new value
 *
 * ### Numeric Operations:
 * - `field += number` - Increment by value
 * - `field -= number` - Decrement by value (Increment with negative)
 * - `field *= number` - Multiply by value
 *
 * ### String Operations:
 * - `field += 'text'` - Append string
 *
 * ### Chaining Operations:
 * - `field1 = value1; field2 += 5` - Chain multiple modifications (separated by semicolon)
 *
 * ### Value Types:
 * - Strings: `'value'` or `"value"`
 * - Numbers: `123`, `45.67`, `-10`
 * - Booleans: `true`, `false`
 * - Null: `null`
 *
 * ## Examples
 *
 * ```
 * // Simple assignment
 * "status = 'active'"
 *
 * // Increment number
 * "age += 1"
 *
 * // Decrement number
 * "count -= 5"
 *
 * // Multiply
 * "score *= 2"
 *
 * // Append string
 * "name += ' Jr.'"
 *
 * // Multiple modifications
 * "age += 1; lastUpdated = '2024-01-15T10:30:00Z'"
 *
 * // Complex chain
 * "status = 'published'; publishCount += 1; publishedAt = '2024-01-15T10:30:00Z'"
 * ```
 */
@JvmInline
@Serializable(ModificationExpressionSerializer::class)
public value class ModificationExpression<T>(public val modification: Modification<T>) {
    public companion object {
        public inline fun <reified T> fromString(string: String): ModificationExpression<T> {
            return fromString(string, serializer<T>())
        }

        public fun <T> fromString(string: String, inner: KSerializer<T>): ModificationExpression<T> {
            return ModificationExpression(ModificationExpressionParser(string, inner).parse())
        }
    }
}

public class ModificationExpressionSerializer<T>(public val inner: KSerializer<T>) :
    KSerializer<ModificationExpression<T>> {

    @OptIn(InternalSerializationApi::class)
    override val descriptor: SerialDescriptor = buildSerialDescriptor("com.lightningkite.lightningserver.ai.ModificationExpression", PrimitiveKind.STRING) {
        this.annotations = listOf(Description(
            """
                An SQL-style modification expression for updating rows in a table.  Some examples:
                status = 'active'
                age += 1
                count -= 5
                score *= 2
                name += ' Jr.'
                age += 1; lastUpdated = '2024-01-15T10:30:00Z'
                status = 'published'; publishCount += 1; publishedAt = '2024-01-15T10:30:00Z'
            """.trimIndent()
        ))
    }

    override fun serialize(
        encoder: Encoder,
        value: ModificationExpression<T>
    ): Unit = encoder.encodeString(value.modification.toString())

    override fun deserialize(decoder: Decoder): ModificationExpression<T> =
        ModificationExpression(ModificationExpressionParser(decoder.decodeString(), inner).parse())
}

/**
 * Exception thrown when a ModificationExpression cannot be parsed.
 */
internal class ModificationParseException(
    message: String,
    cause: Throwable? = null,
    public val position: Int? = null,
    public val expression: String? = null
) : Exception(buildMessage(message, position, expression), cause) {

    private companion object {
        fun buildMessage(message: String, position: Int?, expression: String?): String {
            if (position == null || expression == null) return message

            val contextStart = maxOf(0, position - 20)
            val contextEnd = minOf(expression.length, position + 20)
            val context = expression.substring(contextStart, contextEnd)
            val pointer = " ".repeat(position - contextStart) + "^"

            return buildString {
                appendLine(message)
                appendLine("Position: $position")
                if (contextStart > 0) append("...")
                appendLine(context)
                if (contextStart > 0) append("   ")
                appendLine(pointer)
            }
        }
    }
}

/**
 * Parser for ModificationExpression strings.
 */
internal class ModificationExpressionParser<T>(
    private val expression: String,
    private val modelSerializer: KSerializer<T>
) {
    private var position = 0

    private fun error(message: String): ModificationParseException {
        return ModificationParseException(message, position = position, expression = expression)
    }

    fun parse(): Modification<T> {
        skipWhitespace()
        if (position >= expression.length) {
            throw error("Empty expression. Expected a modification like 'field = value' or 'field += 1'")
        }

        val modifications = mutableListOf<Modification<T>>()

        while (true) {
            skipWhitespace()
            if (position >= expression.length) break

            modifications.add(parseFieldModification())
            skipWhitespace()

            if (position >= expression.length) break

            if (expression[position] == ';') {
                position++
                continue
            } else {
                throw error("Expected ';' to separate modifications or end of expression")
            }
        }

        return when {
            modifications.isEmpty() -> throw error("No modifications found")
            modifications.size == 1 -> modifications[0]
            else -> Modification.Chain(modifications)
        }
    }

    private fun parseFieldModification(): Modification<T> {
        val fieldName = parseIdentifier()
        skipWhitespace()

        val property = getProperty(fieldName)

        skipWhitespace()

        return when {
            matchOperator("+=") -> {
                skipWhitespace()
                val value = parseValue(property)
                createIncrementOrAppend(fieldName, property, value)
            }

            matchOperator("-=") -> {
                skipWhitespace()
                val value = parseValue(property)
                createDecrement(fieldName, property, value)
            }

            matchOperator("*=") -> {
                skipWhitespace()
                val value = parseValue(property)
                createMultiply(fieldName, property, value)
            }

            matchOperator("=") -> {
                skipWhitespace()
                val value = parseValue(property)
                @Suppress("UNCHECKED_CAST")
                Modification.OnField(property as SerializableProperty<T, Any?>, Modification.Assign(value))
            }

            else -> throw error(
                "Expected modification operator after field '$fieldName'. " +
                        "Valid operators: =, +=, -=, *="
            )
        }
    }

    private fun createIncrementOrAppend(
        fieldName: String,
        property: SerializableProperty<T, *>,
        value: Any?
    ): Modification<T> {
        val kind = property.serializer.descriptor.kind

        @Suppress("UNCHECKED_CAST")
        return when {
            kind == PrimitiveKind.STRING -> {
                if (value !is String) {
                    throw error("Cannot append non-string value to string field '$fieldName'")
                }
                Modification.OnField(
                    property as SerializableProperty<T, String>,
                    Modification.AppendString(value)
                )
            }

            isNumericKind(kind) -> {
                if (value !is Number) {
                    throw error("Cannot increment non-numeric field '$fieldName' with non-numeric value")
                }
                val convertedValue = convertNumberToFieldType(value, kind)
                Modification.OnField(
                    property as SerializableProperty<T, Number>,
                    Modification.Increment(convertedValue)
                )
            }

            else -> throw error(
                "Field '$fieldName' does not support += operator. " +
                        "Only numeric and string fields support this operation."
            )
        }
    }

    private fun createDecrement(
        fieldName: String,
        property: SerializableProperty<T, *>,
        value: Any?
    ): Modification<T> {
        val kind = property.serializer.descriptor.kind

        @Suppress("UNCHECKED_CAST")
        return when {
            isNumericKind(kind) -> {
                if (value !is Number) {
                    throw error("Cannot decrement non-numeric field '$fieldName' with non-numeric value")
                }
                // Negate the value for decrement
                val negatedValue = when (value) {
                    is Int -> -value
                    is Long -> -value
                    is Double -> -value
                    is Float -> -value
                    else -> throw error("Unsupported numeric type for decrement")
                }
                val convertedValue = convertNumberToFieldType(negatedValue, kind)
                Modification.OnField(
                    property as SerializableProperty<T, Number>,
                    Modification.Increment(convertedValue)
                )
            }

            else -> throw error(
                "Field '$fieldName' does not support -= operator. " +
                        "Only numeric fields support this operation."
            )
        }
    }

    private fun createMultiply(
        fieldName: String,
        property: SerializableProperty<T, *>,
        value: Any?
    ): Modification<T> {
        val kind = property.serializer.descriptor.kind

        @Suppress("UNCHECKED_CAST")
        return when {
            isNumericKind(kind) -> {
                if (value !is Number) {
                    throw error("Cannot multiply non-numeric field '$fieldName' with non-numeric value")
                }
                val convertedValue = convertNumberToFieldType(value, kind)
                Modification.OnField(
                    property as SerializableProperty<T, Number>,
                    Modification.Multiply(convertedValue)
                )
            }

            else -> throw error(
                "Field '$fieldName' does not support *= operator. " +
                        "Only numeric fields support this operation."
            )
        }
    }

    private fun isNumericKind(kind: kotlinx.serialization.descriptors.SerialKind): Boolean {
        return kind == PrimitiveKind.INT ||
                kind == PrimitiveKind.LONG ||
                kind == PrimitiveKind.DOUBLE ||
                kind == PrimitiveKind.FLOAT ||
                kind == PrimitiveKind.SHORT ||
                kind == PrimitiveKind.BYTE
    }

    private fun convertNumberToFieldType(value: Number, kind: kotlinx.serialization.descriptors.SerialKind): Number {
        return when (kind) {
            PrimitiveKind.DOUBLE -> value.toDouble()
            PrimitiveKind.FLOAT -> value.toFloat()
            PrimitiveKind.LONG -> value.toLong()
            PrimitiveKind.INT -> value.toInt()
            PrimitiveKind.SHORT -> value.toShort()
            PrimitiveKind.BYTE -> value.toByte()
            else -> value
        }
    }

    private fun getProperty(fieldName: String): SerializableProperty<T, *> {
        val properties = modelSerializer.descriptor.elementNames()
        if (fieldName !in properties) {
            val suggestions = properties.filter { it.contains(fieldName, ignoreCase = true) }
            val suggestionText = if (suggestions.isNotEmpty()) {
                "\nDid you mean: ${suggestions.joinToString(", ")}?"
            } else {
                ""
            }
            throw error(
                "Unknown field '$fieldName'. Available fields: ${properties.joinToString(", ")}$suggestionText"
            )
        }

        val serializableProps = modelSerializer.serializableProperties
            ?: throw error("Model serializer does not support field access. Ensure your model is annotated with @GenerateDataClassPaths.")

        return serializableProps.find { it.name == fieldName }
            ?: throw error("Property '$fieldName' not found in model (this shouldn't happen - please report this bug)")
    }

    private fun parseIdentifier(): String {
        skipWhitespace()
        val start = position

        if (position >= expression.length) {
            throw error("Expected field name but reached end of expression")
        }

        if (!expression[position].isLetter() && expression[position] != '_') {
            throw error("Expected field name, but found '${expression[position]}'")
        }

        while (position < expression.length && (expression[position].isLetterOrDigit() || expression[position] == '_')) {
            position++
        }

        if (position == start) {
            throw error("Expected field name")
        }

        return expression.substring(start, position)
    }

    private fun parseValue(property: SerializableProperty<T, *>): Any? {
        skipWhitespace()

        if (position >= expression.length) {
            throw error("Expected value but reached end of expression")
        }

        val ch = expression[position]

        // String value
        if (ch == '"' || ch == '\'') {
            return parseStringValue()
        }

        // null
        if (matchKeyword("null")) {
            return null
        }

        // Boolean
        if (matchKeyword("true")) {
            return true
        }
        if (matchKeyword("false")) {
            return false
        }

        // Number
        if (ch == '-' || ch.isDigit()) {
            return try {
                parseNumber()
            } catch (e: NumberFormatException) {
                throw error("Invalid number format: ${e.message}")
            }
        }

        throw error(
            "Expected value (string, number, boolean, or null) but found '$ch'. " +
                    "Remember: strings must be quoted with ' or \""
        )
    }

    private fun parseStringValue(): String {
        skipWhitespace()

        if (position >= expression.length) {
            throw error("Expected string value but reached end of expression. String values must be quoted with ' or \"")
        }

        val quote = expression[position]
        if (quote != '"' && quote != '\'') {
            throw error("Expected quoted string value (with ' or \"), but found '${expression[position]}'. Remember to quote string values!")
        }

        val quoteStart = position
        position++
        val start = position

        while (position < expression.length && expression[position] != quote) {
            if (expression[position] == '\\' && position + 1 < expression.length) {
                position += 2 // Skip escaped character
            } else {
                position++
            }
        }

        if (position >= expression.length) {
            throw error("Unterminated string. Missing closing $quote for string that started at position $quoteStart")
        }

        val value = expression.substring(start, position)
        position++ // Skip closing quote
        return value.replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\")
    }

    private fun parseNumber(): Number {
        val start = position
        if (position < expression.length && expression[position] == '-') {
            position++
        }
        while (position < expression.length && expression[position].isDigit()) {
            position++
        }

        // Check for decimal
        if (position < expression.length && expression[position] == '.') {
            position++
            while (position < expression.length && expression[position].isDigit()) {
                position++
            }
            return expression.substring(start, position).toDouble()
        }

        val str = expression.substring(start, position)
        return try {
            str.toInt()
        } catch (e: NumberFormatException) {
            str.toLong()
        }
    }

    private fun skipWhitespace() {
        while (position < expression.length && expression[position].isWhitespace()) {
            position++
        }
    }

    private fun matchKeyword(keyword: String): Boolean {
        skipWhitespace()
        val end = position + keyword.length
        if (end > expression.length) {
            return false
        }

        val match = expression.substring(position, end).equals(keyword, ignoreCase = true)
        if (match) {
            // Make sure the keyword is not part of a larger identifier
            if (end < expression.length && (expression[end].isLetterOrDigit() || expression[end] == '_')) {
                return false
            }
            position = end
        }
        return match
    }

    private fun matchOperator(operator: String): Boolean {
        skipWhitespace()
        val end = position + operator.length
        if (end > expression.length) {
            return false
        }

        val match = expression.substring(position, end) == operator
        if (match) {
            // For single-char operators like "=", ensure we're not matching part of "==" or similar
            if (operator == "=" && end < expression.length && expression[end] == '=') {
                return false
            }
            position = end
        }
        return match
    }
}

/**
 * Extension function to get element names from a descriptor.
 */
private fun kotlinx.serialization.descriptors.SerialDescriptor.elementNames(): List<String> {
    return (0 until elementsCount).map { getElementName(it) }
}
