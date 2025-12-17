package com.lightningkite.lightningserver.ai

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Serializable
import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

@Serializable
@GenerateDataClassPaths
data class ErrorTestModel(
    override val _id: Uuid = Uuid.random(),
    val name: String,
    val age: Int,
    val active: Boolean
) : HasId<Uuid>

/**
 * Test suite for verifying improved error messages in ConditionExpression parser.
 */
class ConditionExpressionErrorMessageTest {

    @Test
    fun testEmptyExpression() {
        val exception = assertFailsWith<ConditionParseException> {
            ConditionExpression.fromString<ErrorTestModel>("")
        }
        assertContains(exception.message!!, "Empty expression")
        assertContains(exception.message!!, "Expected a condition")
    }

    @Test
    fun testUnknownField() {
        val exception = assertFailsWith<ConditionParseException> {
            ConditionExpression.fromString<ErrorTestModel>("unknownField = 'value'")
        }
        assertContains(exception.message!!, "Unknown field 'unknownField'")
        assertContains(exception.message!!, "Available fields")
        assertContains(exception.message!!, "Position:")
    }

    @Test
    fun testUnknownFieldWithSuggestion() {
        val exception = assertFailsWith<ConditionParseException> {
            ConditionExpression.fromString<ErrorTestModel>("nam = 'value'")
        }
        assertContains(exception.message!!, "Unknown field 'nam'")
        assertContains(exception.message!!, "Did you mean: name")
    }

    @Test
    fun testMissingOperator() {
        val exception = assertFailsWith<ConditionParseException> {
            ConditionExpression.fromString<ErrorTestModel>("name 'admin'")
        }
        assertContains(exception.message!!, "Expected comparison operator")
        assertContains(exception.message!!, "Valid operators")
    }

    @Test
    fun testUnterminatedString() {
        val exception = assertFailsWith<ConditionParseException> {
            ConditionExpression.fromString<ErrorTestModel>("name = 'admin")
        }
        assertContains(exception.message!!, "Unterminated string")
        assertContains(exception.message!!, "Missing closing")
    }

    @Test
    fun testUnmatchedParenthesis() {
        val exception = assertFailsWith<ConditionParseException> {
            ConditionExpression.fromString<ErrorTestModel>("(name = 'admin'")
        }
        assertContains(exception.message!!, "Expected ')'")
    }

    @Test
    fun testTrailingCharacters() {
        val exception = assertFailsWith<ConditionParseException> {
            ConditionExpression.fromString<ErrorTestModel>("name = 'admin' xyz")
        }
        assertContains(exception.message!!, "Unexpected characters after complete expression")
        assertContains(exception.message!!, "missing an operator")
    }

    @Test
    fun testMissingValueAfterOperator() {
        val exception = assertFailsWith<ConditionParseException> {
            ConditionExpression.fromString<ErrorTestModel>("name =")
        }
        assertContains(exception.message!!, "Expected value")
        assertContains(exception.message!!, "end of expression")
    }

    @Test
    fun testUnquotedString() {
        val exception = assertFailsWith<ConditionParseException> {
            ConditionExpression.fromString<ErrorTestModel>("name = admin")
        }
        assertContains(exception.message!!, "Expected value")
        assertContains(exception.message!!, "strings must be quoted")
    }

    @Test
    fun testInvalidNumberFormat() {
        val exception = assertFailsWith<ConditionParseException> {
            ConditionExpression.fromString<ErrorTestModel>("age = 12.34.56")
        }
        // This will be caught when trying to use the comparison
        assertContains(exception.message!!, "Unexpected characters")
    }

    @Test
    fun testNotWithoutIn() {
        val exception = assertFailsWith<ConditionParseException> {
            ConditionExpression.fromString<ErrorTestModel>("name NOT 'admin'")
        }
        assertContains(exception.message!!, "Expected 'IN' after 'NOT'")
        assertContains(exception.message!!, "NOT IN")
    }

    @Test
    fun testInWithoutParenthesis() {
        val exception = assertFailsWith<ConditionParseException> {
            ConditionExpression.fromString<ErrorTestModel>("name IN 'admin', 'user'")
        }
        assertContains(exception.message!!, "Expected '(' to start value list")
    }

    @Test
    fun testTrailingCommaInList() {
        val exception = assertFailsWith<ConditionParseException> {
            ConditionExpression.fromString<ErrorTestModel>("name IN ('admin', 'user',)")
        }
        assertContains(exception.message!!, "Expected value in list")
        assertContains(exception.message!!, "Remove trailing commas")
    }

    @Test
    fun testUnterminatedList() {
        val exception = assertFailsWith<ConditionParseException> {
            ConditionExpression.fromString<ErrorTestModel>("name IN ('admin', 'user'")
        }
        assertContains(exception.message!!, "Unterminated value list")
        assertContains(exception.message!!, "Missing closing ')'")
    }

    @Test
    fun testFieldNameStartsWithNumber() {
        val exception = assertFailsWith<ConditionParseException> {
            ConditionExpression.fromString<ErrorTestModel>("9name = 'value'")
        }
        assertContains(exception.message!!, "Field name must start with a letter or underscore")
    }

    @Test
    fun testStringOperatorOnNonStringField() {
        val exception = assertFailsWith<ConditionParseException> {
            ConditionExpression.fromString<ErrorTestModel>("age CONTAINS '5'")
        }
        assertContains(exception.message!!, "not a String type")
        assertContains(exception.message!!, "CONTAINS can only be used with String fields")
    }

    @Test
    fun testContextualErrorDisplay() {
        val exception = assertFailsWith<ConditionParseException> {
            ConditionExpression.fromString<ErrorTestModel>("name = 'Alice' AND age > 25 AND status = 'active'")
        }
        // Should show position and context around the error
        assertContains(exception.message!!, "Position:")
        assertContains(exception.message!!, "status") // The problematic field should be in context
        assertContains(exception.message!!, "^") // Pointer to error position
    }

    @Test
    fun testPrintErrorExamples() {
        println("\n=== Error Message Examples ===\n")

        val errorExamples = listOf(
            "" to "Empty expression",
            "unknownField = 'value'" to "Unknown field",
            "nam = 'value'" to "Unknown field with suggestion",
            "name = 'unterminated" to "Unterminated string",
            "name = admin" to "Unquoted string value",
            "name NOT 'admin'" to "NOT without IN",
            "name IN ('a', 'b',)" to "Trailing comma in list",
            "age CONTAINS '5'" to "Wrong operator for field type",
            "(name = 'Alice'" to "Unmatched parenthesis"
        )

        for ((expression, description) in errorExamples) {
            println("$description:")
            println("  Expression: '$expression'")
            try {
                val r = ConditionExpression.fromString<ErrorTestModel>(expression)
                throw Exception("  ERROR: Should have thrown exception!  Got $r instead")
            } catch (e: ConditionParseException) {
                println("  Error message:")
                e.message?.lines()?.forEach { line ->
                    println("    $line")
                }
            }
            println()
        }
    }
}
