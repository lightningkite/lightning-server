package com.lightningkite.lightningserver.ai

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Serializable
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@Serializable
@GenerateDataClassPaths
data class TestUser(
    override val _id: Uuid = Uuid.random(),
    val name: String,
    val age: Int,
    val role: String,
    val active: Boolean,
    val salary: Double
) : HasId<Uuid>

class ConditionExpressionTest {

    private val admin = TestUser(
        name = "Alice",
        age = 30,
        role = "admin",
        active = true,
        salary = 100000.0
    )

    private val moderator = TestUser(
        name = "Bob",
        age = 25,
        role = "moderator",
        active = true,
        salary = 75000.0
    )

    private val user = TestUser(
        name = "Charlie",
        age = 20,
        role = "user",
        active = false,
        salary = 50000.0
    )

    @Test
    fun testSimpleEquality() {
        val condition = ConditionExpression.fromString<TestUser>("role = 'admin'").condition

        assertTrue(condition(admin))
        assertFalse(condition(moderator))
        assertFalse(condition(user))
    }

    @Test
    fun testNotEqual() {
        val condition = ConditionExpression.fromString<TestUser>("role != 'admin'").condition

        assertFalse(condition(admin))
        assertTrue(condition(moderator))
        assertTrue(condition(user))
    }

    @Test
    fun testNotEqualAlternativeSyntax() {
        val condition = ConditionExpression.fromString<TestUser>("role <> 'admin'").condition

        assertFalse(condition(admin))
        assertTrue(condition(moderator))
        assertTrue(condition(user))
    }

    @Test
    fun testGreaterThan() {
        val condition = ConditionExpression.fromString<TestUser>("age > 25").condition

        assertTrue(condition(admin))
        assertFalse(condition(moderator))
        assertFalse(condition(user))
    }

    @Test
    fun testGreaterThanOrEqual() {
        val condition = ConditionExpression.fromString<TestUser>("age >= 25").condition

        assertTrue(condition(admin))
        assertTrue(condition(moderator))
        assertFalse(condition(user))
    }

    @Test
    fun testLessThan() {
        val condition = ConditionExpression.fromString<TestUser>("age < 25").condition

        assertFalse(condition(admin))
        assertFalse(condition(moderator))
        assertTrue(condition(user))
    }

    @Test
    fun testLessThanOrEqual() {
        val condition = ConditionExpression.fromString<TestUser>("age <= 25").condition

        assertFalse(condition(admin))
        assertTrue(condition(moderator))
        assertTrue(condition(user))
    }

    @Test
    fun testDoubleComparison() {
        val condition = ConditionExpression.fromString<TestUser>("salary >= 75000.0").condition

        assertTrue(condition(admin))
        assertTrue(condition(moderator))
        assertFalse(condition(user))
    }

    @Test
    fun testBooleanEquality() {
        val condition = ConditionExpression.fromString<TestUser>("active = true").condition

        assertTrue(condition(admin))
        assertTrue(condition(moderator))
        assertFalse(condition(user))
    }

    @Test
    fun testStringContainsCaseSensitive() {
        val condition = ConditionExpression.fromString<TestUser>("name CONTAINS 'li'").condition

        assertTrue(condition(admin)) // "Alice" contains "li"
        assertFalse(condition(moderator)) // "Bob" doesn't contain "li"
        assertTrue(condition(user)) // "Charlie" contains "li"
    }

    @Test
    fun testStringContainsCaseInsensitive() {
        val condition = ConditionExpression.fromString<TestUser>("name ICONTAINS 'BOB'").condition

        assertFalse(condition(admin))
        assertTrue(condition(moderator)) // "Bob" contains "BOB" (case-insensitive)
        assertFalse(condition(user))
    }

    @Test
    fun testRegexMatches() {
        val condition = ConditionExpression.fromString<TestUser>("name MATCHES '^A.*'").condition

        assertTrue(condition(admin)) // "Alice" starts with A
        assertFalse(condition(moderator))
        assertFalse(condition(user))
    }

    @Test
    fun testRegexMatchesCaseInsensitive() {
        val condition = ConditionExpression.fromString<TestUser>("name IMATCHES '^a.*'").condition

        assertTrue(condition(admin)) // "Alice" starts with A (case-insensitive)
        assertFalse(condition(moderator))
        assertFalse(condition(user))
    }

    @Test
    fun testInOperator() {
        val condition = ConditionExpression.fromString<TestUser>("role IN ('admin', 'moderator')").condition

        assertTrue(condition(admin))
        assertTrue(condition(moderator))
        assertFalse(condition(user))
    }

    @Test
    fun testNotInOperator() {
        val condition = ConditionExpression.fromString<TestUser>("role NOT IN ('admin', 'moderator')").condition

        assertFalse(condition(admin))
        assertFalse(condition(moderator))
        assertTrue(condition(user))
    }

    @Test
    fun testAndOperator() {
        val condition = ConditionExpression.fromString<TestUser>("role = 'admin' AND active = true").condition

        assertTrue(condition(admin))
        assertFalse(condition(moderator))
        assertFalse(condition(user))
    }

    @Test
    fun testOrOperator() {
        val condition = ConditionExpression.fromString<TestUser>("role = 'admin' OR role = 'moderator'").condition

        assertTrue(condition(admin))
        assertTrue(condition(moderator))
        assertFalse(condition(user))
    }

    @Test
    fun testNotOperator() {
        val condition = ConditionExpression.fromString<TestUser>("NOT active = true").condition

        assertFalse(condition(admin))
        assertFalse(condition(moderator))
        assertTrue(condition(user))
    }

    @Test
    fun testComplexNestedCondition() {
        val condition = ConditionExpression.fromString<TestUser>("(role = 'admin' OR role = 'moderator') AND active = true").condition

        assertTrue(condition(admin))
        assertTrue(condition(moderator))
        assertFalse(condition(user))
    }

    @Test
    fun testComplexNestedConditionWithNot() {
        val condition = ConditionExpression.fromString<TestUser>("(role = 'admin' OR role = 'moderator') AND NOT active = false").condition

        assertTrue(condition(admin))
        assertTrue(condition(moderator))
        assertFalse(condition(user))
    }

    @Test
    fun testMultipleAndConditions() {
        val condition = ConditionExpression.fromString<TestUser>("role = 'admin' AND active = true AND age >= 25").condition

        assertTrue(condition(admin))
        assertFalse(condition(moderator))
        assertFalse(condition(user))
    }

    @Test
    fun testAlwaysCondition() {
        val condition = ConditionExpression.fromString<TestUser>("true").condition

        assertTrue(condition(admin))
        assertTrue(condition(moderator))
        assertTrue(condition(user))
    }

    @Test
    fun testAlwaysConditionAlternative() {
        val condition = ConditionExpression.fromString<TestUser>("1=1").condition

        assertTrue(condition(admin))
        assertTrue(condition(moderator))
        assertTrue(condition(user))
    }

    @Test
    fun testNeverCondition() {
        val condition = ConditionExpression.fromString<TestUser>("false").condition

        assertFalse(condition(admin))
        assertFalse(condition(moderator))
        assertFalse(condition(user))
    }

    @Test
    fun testNeverConditionAlternative() {
        val condition = ConditionExpression.fromString<TestUser>("1=0").condition

        assertFalse(condition(admin))
        assertFalse(condition(moderator))
        assertFalse(condition(user))
    }

    @Test
    fun testWhitespaceHandling() {
        val condition = ConditionExpression.fromString<TestUser>("  role   =   'admin'  ").condition

        assertTrue(condition(admin))
        assertFalse(condition(moderator))
    }

    @Test
    fun testNegativeNumbers() {
        val condition = ConditionExpression.fromString<TestUser>("age > -1").condition

        assertTrue(condition(admin))
        assertTrue(condition(moderator))
        assertTrue(condition(user))
    }

    @Test
    fun testDoubleQuotes() {
        val condition = ConditionExpression.fromString<TestUser>("role = \"admin\"").condition

        assertTrue(condition(admin))
        assertFalse(condition(moderator))
    }

    @Test
    fun testEscapedQuotes() {
        val testUserWithQuotes = TestUser(
            name = "Alice's Friend",
            age = 30,
            role = "admin",
            active = true,
            salary = 100000.0
        )

        val condition = ConditionExpression.fromString<TestUser>("name = 'Alice\\'s Friend'").condition

        assertTrue(condition(testUserWithQuotes))
        assertFalse(condition(admin))
    }

    @Test
    fun testEmptyInList() {
        val condition = ConditionExpression.fromString<TestUser>("role IN ()").condition

        assertFalse(condition(admin))
        assertFalse(condition(moderator))
        assertFalse(condition(user))
    }

    // Error cases

    @Test
    fun testEmptyExpression() {
        assertFailsWith<ConditionParseException> {
            ConditionExpression.fromString<TestUser>("")
        }
    }

    @Test
    fun testUnknownField() {
        val exception = assertFailsWith<ConditionParseException> {
            ConditionExpression.fromString<TestUser>("unknownField = 'value'")
        }
        assertTrue(exception.message!!.contains("Unknown field"))
    }

    @Test
    fun testMissingOperator() {
        assertFailsWith<ConditionParseException> {
            ConditionExpression.fromString<TestUser>("role 'admin'")
        }
    }

    @Test
    fun testUnterminatedString() {
        assertFailsWith<ConditionParseException> {
            ConditionExpression.fromString<TestUser>("role = 'admin")
        }
    }

    @Test
    fun testUnmatchedParenthesis() {
        assertFailsWith<ConditionParseException> {
            ConditionExpression.fromString<TestUser>("(role = 'admin'")
        }
    }

    @Test
    fun testInvalidSyntax() {
        assertFailsWith<ConditionParseException> {
            ConditionExpression.fromString<TestUser>("role = = 'admin'")
        }
    }

    @Test
    fun testMissingValueInInList() {
        assertFailsWith<ConditionParseException> {
            ConditionExpression.fromString<TestUser>("role IN (,)")
        }
    }

    @Test
    fun testTrailingCommaInList() {
        assertFailsWith<ConditionParseException> {
            ConditionExpression.fromString<TestUser>("role IN ('admin', 'moderator', ")
        }
    }

    // Operator precedence tests

    @Test
    fun testOperatorPrecedenceAndBeforeOr() {
        // This should be parsed as: (role = 'admin' AND active = true) OR (role = 'moderator')
        val condition = ConditionExpression.fromString<TestUser>("role = 'admin' AND active = true OR role = 'moderator'").condition

        assertTrue(condition(admin)) // admin AND active
        assertTrue(condition(moderator)) // moderator
        assertFalse(condition(user)) // neither
    }

    @Test
    fun testOperatorPrecedenceWithParentheses() {
        // This should be parsed as: role = 'admin' AND (active = true OR role = 'moderator')
        val condition = ConditionExpression.fromString<TestUser>("role = 'admin' AND (active = true OR role = 'moderator')").condition

        assertTrue(condition(admin)) // admin AND active
        assertFalse(condition(moderator)) // moderator but not admin
        assertFalse(condition(user)) // neither
    }

    @Test
    fun testCaseInsensitiveKeywords() {
        val condition = ConditionExpression.fromString<TestUser>("role = 'admin' and active = true or role = 'moderator'").condition

        assertTrue(condition(admin))
        assertTrue(condition(moderator))
        assertFalse(condition(user))
    }

    @Test
    fun testNumericValueList() {
        val condition = ConditionExpression.fromString<TestUser>("age IN (20, 25, 30)").condition

        assertTrue(condition(admin)) // age 30
        assertTrue(condition(moderator)) // age 25
        assertTrue(condition(user)) // age 20
    }

    @Test
    fun testConditionToString() {
        val condition = ConditionExpression.fromString<TestUser>("role = 'admin'").condition

        // Just ensure toString doesn't throw
        val str = condition.toString()
        assertTrue(str.isNotEmpty())
    }

    @Test
    fun testPrintExamples() {
        println("\n=== ConditionExpression Examples ===\n")

        val examples = listOf(
            "role = 'admin'" to "Simple equality",
            "age > 18" to "Greater than comparison",
            "name ICONTAINS 'alice'" to "Case-insensitive string search",
            "role IN ('admin', 'moderator')" to "Set membership",
            "role = 'admin' AND active = true" to "Logical AND",
            "role = 'admin' OR role = 'moderator'" to "Logical OR",
            "(role = 'admin' OR role = 'moderator') AND active = true" to "Complex nested query",
            "true" to "Match all records"
        )

        for ((expression, description) in examples) {
            println("$description:")
            println("  Expression: $expression")
            val condition = ConditionExpression.fromString<TestUser>(expression).condition
            println("  Parsed: $condition")
            println()
        }
    }
}
