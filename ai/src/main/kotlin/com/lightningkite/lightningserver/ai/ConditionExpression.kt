package com.lightningkite.lightningserver.ai

import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.SerializableProperty
import com.lightningkite.services.database.serializableProperties
import jdk.jfr.Description
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.serializer
import kotlin.jvm.JvmInline

/**
 * A value class that wraps an SQL-like expression string that can be parsed into a Condition<T>.
 *
 * This provides a simpler alternative to the generic Condition<T> type for LLM tool integration,
 * since Koog ToolDescriptor doesn't support generics.
 *
 * ## Supported Syntax
 *
 * ### Comparison Operators:
 * - `field = value` - Equal
 * - `field != value` or `field <> value` - NotEqual
 * - `field > value` - GreaterThan
 * - `field >= value` - GreaterThanOrEqual
 * - `field < value` - LessThan
 * - `field <= value` - LessThanOrEqual
 *
 * ### Set Operations:
 * - `field IN (value1, value2, value3)` - Inside
 * - `field NOT IN (value1, value2, value3)` - NotInside
 *
 * ### String Operations:
 * - `field CONTAINS 'substring'` - StringContains (case-sensitive)
 * - `field ICONTAINS 'substring'` - StringContains (case-insensitive)
 * - `field MATCHES 'pattern'` - RegexMatches (case-sensitive)
 * - `field IMATCHES 'pattern'` - RegexMatches (case-insensitive)
 *
 * ### Logical Operators:
 * - `condition1 AND condition2` - And
 * - `condition1 OR condition2` - Or
 * - `NOT condition` - Not
 * - `(condition)` - Grouping with parentheses
 *
 * ### Special Values:
 * - `true` or `1=1` - Always (match all)
 * - `false` or `1=0` - Never (match none)
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
 * // Simple equality
 * "role = 'admin'"
 *
 * // Comparison
 * "age > 18"
 *
 * // String search
 * "name ICONTAINS 'john'"
 *
 * // Set membership
 * "status IN ('active', 'pending')"
 *
 * // Logical AND
 * "role = 'admin' AND active = true"
 *
 * // Logical OR
 * "status = 'active' OR status = 'pending'"
 *
 * // Complex nested query
 * "(role = 'admin' OR role = 'moderator') AND active = true"
 *
 * // Match all
 * "true"
 * ```
 */
@JvmInline
@Description("""
An SQL-style condition expression for querying a table.  Some examples:

role = 'admin'
age > 18
name ICONTAINS 'john'
status IN ('active', 'pending')
role = 'admin' AND active = true
status = 'active' OR status = 'pending'
(role = 'admin' OR role = 'moderator') AND active = true"
true
""")
@Serializable(ConditionExpressionSerializer::class)
public value class ConditionExpression<T>(public val condition: Condition<T>) {
    public companion object {
        public inline fun <reified T> fromString(string: String): ConditionExpression<T> {
            return fromString(string, serializer<T>())
        }
        public fun <T> fromString(string: String, inner: KSerializer<T>): ConditionExpression<T> {
            return ConditionExpression(ConditionExpressionParser(string, inner).parse())
        }
    }
}

public class ConditionExpressionSerializer<T>(public val inner: KSerializer<T>): KSerializer<ConditionExpression<T>> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.lightningkite.lightningserver.ai.ConditionExpression", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: ConditionExpression<T>
    ): Unit = encoder.encodeString(value.condition.toString())

    override fun deserialize(decoder: Decoder): ConditionExpression<T> = ConditionExpression(ConditionExpressionParser(decoder.decodeString(), inner).parse())

}

/**
 * Exception thrown when a ConditionExpression cannot be parsed.
 *
 * @property message The error message
 * @property position The position in the expression where the error occurred (if available)
 * @property expression The full expression being parsed (if available)
 */
internal class ConditionParseException(
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
 * Parser for ConditionExpression strings.
 */
internal class ConditionExpressionParser<T>(
    private val expression: String,
    private val modelSerializer: KSerializer<T>
) {
    private var position = 0

    private fun error(message: String): ConditionParseException {
        return ConditionParseException(message, position = position, expression = expression)
    }

    fun parse(): Condition<T> {
        skipWhitespace()
        if (position >= expression.length) {
            throw error("Empty expression. Expected a condition like 'field = value' or 'true'")
        }
        val condition = parseOr()
        skipWhitespace()
        if (position < expression.length) {
            throw error(
                "Unexpected characters after complete expression. " +
                "Perhaps you're missing an operator (AND, OR) or have a typo?"
            )
        }
        return condition
    }

    private fun parseOr(): Condition<T> {
        var left = parseAnd()
        skipWhitespace()

        while (matchKeyword("OR")) {
            skipWhitespace()
            val right = parseAnd()
            left = Condition.Or(left, right)
            skipWhitespace()
        }

        return left
    }

    private fun parseAnd(): Condition<T> {
        var left = parseNot()
        skipWhitespace()

        while (matchKeyword("AND")) {
            skipWhitespace()
            val right = parseNot()
            left = Condition.And(left, right)
            skipWhitespace()
        }

        return left
    }

    private fun parseNot(): Condition<T> {
        skipWhitespace()
        if (matchKeyword("NOT")) {
            skipWhitespace()
            val condition = parseNot()
            return Condition.Not(condition)
        }
        return parsePrimary()
    }

    private fun parsePrimary(): Condition<T> {
        skipWhitespace()

        // Handle parentheses
        if (peek() == '(') {
            consume('(')
            val condition = parseOr()
            skipWhitespace()
            consume(')')
            return condition
        }

        // Handle special keywords
        if (matchKeyword("true") || matchKeyword("1=1")) {
            return Condition.Always
        }
        if (matchKeyword("false") || matchKeyword("1=0")) {
            return Condition.Never
        }

        // Parse field comparison
        return parseFieldComparison()
    }

    private fun parseFieldComparison(): Condition<T> {
        val fieldName = parseIdentifier()
        skipWhitespace()

        // Get the property for this field
        val property = getProperty(fieldName)

        // Parse the operator and create the appropriate condition
        return when {
            matchKeyword("ICONTAINS") -> {
                if (!isStringProperty(property)) {
                    throw error("Field '$fieldName' is not a String type. ICONTAINS can only be used with String fields.")
                }
                skipWhitespace()
                val value = parseStringValue()
                @Suppress("UNCHECKED_CAST")
                Condition.OnField(
                    property as SerializableProperty<T, String>,
                    Condition.StringContains(value, ignoreCase = true)
                )
            }

            matchKeyword("CONTAINS") -> {
                if (!isStringProperty(property)) {
                    throw error("Field '$fieldName' is not a String type. CONTAINS can only be used with String fields.")
                }
                skipWhitespace()
                val value = parseStringValue()
                @Suppress("UNCHECKED_CAST")
                Condition.OnField(
                    property as SerializableProperty<T, String>,
                    Condition.StringContains(value, ignoreCase = false)
                )
            }

            matchKeyword("IMATCHES") -> {
                if (!isStringProperty(property)) {
                    throw error("Field '$fieldName' is not a String type. IMATCHES can only be used with String fields.")
                }
                skipWhitespace()
                val value = parseStringValue()
                @Suppress("UNCHECKED_CAST")
                try {
                    Condition.OnField(
                        property as SerializableProperty<T, String>,
                        Condition.RegexMatches(value, ignoreCase = true)
                    )
                } catch (e: Exception) {
                    throw error("Invalid regular expression pattern: ${e.message}")
                }
            }

            matchKeyword("MATCHES") -> {
                if (!isStringProperty(property)) {
                    throw error("Field '$fieldName' is not a String type. MATCHES can only be used with String fields.")
                }
                skipWhitespace()
                val value = parseStringValue()
                @Suppress("UNCHECKED_CAST")
                try {
                    Condition.OnField(
                        property as SerializableProperty<T, String>,
                        Condition.RegexMatches(value, ignoreCase = false)
                    )
                } catch (e: Exception) {
                    throw error("Invalid regular expression pattern: ${e.message}")
                }
            }

            matchKeyword("NOT") -> {
                skipWhitespace()
                if (!matchKeyword("IN")) {
                    throw error("Expected 'IN' after 'NOT'. Use 'NOT IN (value1, value2, ...)' for set exclusion.")
                }
                skipWhitespace()
                val values = parseValueList(property)
                @Suppress("UNCHECKED_CAST")
                Condition.OnField(property as SerializableProperty<T, Any?>, Condition.NotInside(values))
            }

            matchKeyword("IN") -> {
                skipWhitespace()
                val values = parseValueList(property)
                @Suppress("UNCHECKED_CAST")
                Condition.OnField(property as SerializableProperty<T, Any?>, Condition.Inside(values))
            }

            matchOperator(">=") -> {
                skipWhitespace()
                val value = parseValue(property)
                @Suppress("UNCHECKED_CAST")
                try {
                    Condition.OnField(
                        property as SerializableProperty<T, Comparable<Any>>,
                        Condition.GreaterThanOrEqual(value as Comparable<Any>)
                    )
                } catch (e: ClassCastException) {
                    throw error("Cannot use '>=' operator on field '$fieldName'. This operator requires comparable types (numbers, strings, dates).")
                }
            }

            matchOperator("<=") -> {
                skipWhitespace()
                val value = parseValue(property)
                @Suppress("UNCHECKED_CAST")
                try {
                    Condition.OnField(
                        property as SerializableProperty<T, Comparable<Any>>,
                        Condition.LessThanOrEqual(value as Comparable<Any>)
                    )
                } catch (e: ClassCastException) {
                    throw error("Cannot use '<=' operator on field '$fieldName'. This operator requires comparable types (numbers, strings, dates).")
                }
            }

            matchOperator("<>") || matchOperator("!=") -> {
                skipWhitespace()
                val value = parseValue(property)
                @Suppress("UNCHECKED_CAST")
                Condition.OnField(property as SerializableProperty<T, Any?>, Condition.NotEqual(value))
            }

            matchOperator(">") -> {
                skipWhitespace()
                val value = parseValue(property)
                @Suppress("UNCHECKED_CAST")
                try {
                    Condition.OnField(
                        property as SerializableProperty<T, Comparable<Any>>,
                        Condition.GreaterThan(value as Comparable<Any>)
                    )
                } catch (e: ClassCastException) {
                    throw error("Cannot use '>' operator on field '$fieldName'. This operator requires comparable types (numbers, strings, dates).")
                }
            }

            matchOperator("<") -> {
                skipWhitespace()
                val value = parseValue(property)
                @Suppress("UNCHECKED_CAST")
                try {
                    Condition.OnField(
                        property as SerializableProperty<T, Comparable<Any>>,
                        Condition.LessThan(value as Comparable<Any>)
                    )
                } catch (e: ClassCastException) {
                    throw error("Cannot use '<' operator on field '$fieldName'. This operator requires comparable types (numbers, strings, dates).")
                }
            }

            matchOperator("=") -> {
                skipWhitespace()
                val value = parseValue(property)
                @Suppress("UNCHECKED_CAST")
                Condition.OnField(property as SerializableProperty<T, Any?>, Condition.Equal(value))
            }

            else -> throw error(
                "Expected comparison operator after field '$fieldName'. " +
                "Valid operators: =, !=, <>, <, >, <=, >=, IN, NOT IN, CONTAINS, ICONTAINS, MATCHES, IMATCHES"
            )
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

        // Find the property in the serializer
        val serializableProps = modelSerializer.serializableProperties
            ?: throw error("Model serializer does not support field access. Ensure your model is annotated with @GenerateDataClassPaths.")

        return serializableProps.find { it.name == fieldName }
            ?: throw error("Property '$fieldName' not found in model (this shouldn't happen - please report this bug)")
    }

    private fun isStringProperty(property: SerializableProperty<T, *>): Boolean {
        return property.serializer.descriptor.kind == kotlinx.serialization.descriptors.PrimitiveKind.STRING
    }

    private fun isComparableProperty(property: SerializableProperty<T, *>): Boolean {
        val kind = property.serializer.descriptor.kind
        return kind is kotlinx.serialization.descriptors.PrimitiveKind && kind != kotlinx.serialization.descriptors.PrimitiveKind.BOOLEAN
    }

    private fun parseIdentifier(): String {
        skipWhitespace()
        val start = position

        if (position >= expression.length) {
            throw error("Expected field name but reached end of expression")
        }

        if (!expression[position].isLetter() && expression[position] != '_') {
            throw error("Field name must start with a letter or underscore, but found '${expression[position]}'")
        }

        while (position < expression.length && (expression[position].isLetterOrDigit() || expression[position] == '_')) {
            position++
        }

        if (position == start) {
            throw error("Expected field name")
        }

        return expression.substring(start, position)
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

    private fun parseNumber(): Number {
        val start = position
        if (peek() == '-') {
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

    private fun parseValueList(property: SerializableProperty<T, *>): List<Any?> {
        skipWhitespace()

        if (position >= expression.length) {
            throw error("Expected '(' to start value list but reached end of expression")
        }

        if (expression[position] != '(') {
            throw error("Expected '(' to start value list but found '${expression[position]}'. Use: IN (value1, value2, ...)")
        }

        position++ // consume '('
        val values = mutableListOf<Any?>()

        skipWhitespace()

        // Handle empty list
        if (position < expression.length && expression[position] == ')') {
            position++
            return values
        }

        while (true) {
            skipWhitespace()

            if (position >= expression.length) {
                throw error("Unterminated value list. Missing closing ')'")
            }

            // Check for trailing comma
            if (expression[position] == ',' || expression[position] == ')') {
                throw error("Expected value in list but found '${expression[position]}'. Remove trailing commas or add a value.")
            }

            values.add(parseValue(property))
            skipWhitespace()

            if (position >= expression.length) {
                throw error("Unterminated value list. Missing closing ')'")
            }

            when (expression[position]) {
                ')' -> {
                    position++
                    break
                }
                ',' -> {
                    position++
                    continue
                }
                else -> throw error("Expected ',' or ')' in value list but found '${expression[position]}'")
            }
        }

        return values
    }

    private fun skipWhitespace() {
        while (position < expression.length && expression[position].isWhitespace()) {
            position++
        }
    }

    private fun peek(): Char {
        if (position >= expression.length) {
            throw error("Unexpected end of expression")
        }
        return expression[position]
    }

    private fun consume(expected: Char) {
        if (position >= expression.length) {
            throw error("Expected '$expected' but reached end of expression")
        }
        val ch = expression[position]
        if (ch != expected) {
            throw error("Expected '$expected' but found '$ch'")
        }
        position++
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
            position = end
        }
        return match
    }
}

/**
 * Extension function to get element names from a descriptor.
 */
private fun SerialDescriptor.elementNames(): List<String> {
    return (0 until elementsCount).map { getElementName(it) }
}
