package com.lightningkite.lightningserver.ai

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Serializable
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

@Serializable
@GenerateDataClassPaths
data class TestRecord(
    override val _id: Uuid = Uuid.random(),
    val name: String = "",
    val age: Int = 0,
    val score: Double = 0.0,
    val active: Boolean = false,
    val description: String = ""
) : HasId<Uuid>

class ModificationExpressionTest {

    @Test
    fun testSimpleAssignment() {
        val modification = ModificationExpression.fromString<TestRecord>("name = 'Alice'").modification
        val record = TestRecord()
        val result = modification(record)

        assertEquals("Alice", result.name)
    }

    @Test
    fun testIntegerAssignment() {
        val modification = ModificationExpression.fromString<TestRecord>("age = 25").modification
        val record = TestRecord()
        val result = modification(record)

        assertEquals(25, result.age)
    }

    @Test
    fun testDoubleAssignment() {
        val modification = ModificationExpression.fromString<TestRecord>("score = 95.5").modification
        val record = TestRecord()
        val result = modification(record)

        assertEquals(95.5, result.score)
    }

    @Test
    fun testBooleanAssignment() {
        val modification = ModificationExpression.fromString<TestRecord>("active = true").modification
        val record = TestRecord()
        val result = modification(record)

        assertEquals(true, result.active)
    }

    @Test
    fun testIntegerIncrement() {
        val modification = ModificationExpression.fromString<TestRecord>("age += 5").modification
        val record = TestRecord(age = 20)
        val result = modification(record)

        assertEquals(25, result.age)
    }

    @Test
    fun testIntegerDecrement() {
        val modification = ModificationExpression.fromString<TestRecord>("age -= 3").modification
        val record = TestRecord(age = 20)
        val result = modification(record)

        assertEquals(17, result.age)
    }

    @Test
    fun testDoubleIncrement() {
        val modification = ModificationExpression.fromString<TestRecord>("score += 10.5").modification
        val record = TestRecord(score = 50.0)
        val result = modification(record)

        assertEquals(60.5, result.score)
    }

    @Test
    fun testDoubleDecrement() {
        val modification = ModificationExpression.fromString<TestRecord>("score -= 5.5").modification
        val record = TestRecord(score = 50.0)
        val result = modification(record)

        assertEquals(44.5, result.score)
    }

    @Test
    fun testMultiply() {
        val modification = ModificationExpression.fromString<TestRecord>("age *= 2").modification
        val record = TestRecord(age = 10)
        val result = modification(record)

        assertEquals(20, result.age)
    }

    @Test
    fun testDoubleMultiply() {
        val modification = ModificationExpression.fromString<TestRecord>("score *= 1.5").modification
        val record = TestRecord(score = 100.0)
        val result = modification(record)

        assertEquals(150.0, result.score)
    }

    @Test
    fun testStringAppend() {
        val modification = ModificationExpression.fromString<TestRecord>("name += ' Jr.'").modification
        val record = TestRecord(name = "John")
        val result = modification(record)

        assertEquals("John Jr.", result.name)
    }

    @Test
    fun testStringAppendWithDoubleQuotes() {
        val modification = ModificationExpression.fromString<TestRecord>("name += \" Jr.\"").modification
        val record = TestRecord(name = "John")
        val result = modification(record)

        assertEquals("John Jr.", result.name)
    }

    @Test
    fun testNegativeNumber() {
        val modification = ModificationExpression.fromString<TestRecord>("age = -5").modification
        val record = TestRecord()
        val result = modification(record)

        assertEquals(-5, result.age)
    }

    @Test
    fun testChainedModifications() {
        val modification =
            ModificationExpression.fromString<TestRecord>("name = 'Alice'; age = 25; active = true").modification
        val record = TestRecord()
        val result = modification(record)

        assertEquals("Alice", result.name)
        assertEquals(25, result.age)
        assertEquals(true, result.active)
    }

    @Test
    fun testComplexChain() {
        val modification =
            ModificationExpression.fromString<TestRecord>("name = 'Bob'; age += 1; score *= 2").modification
        val record = TestRecord(name = "Alice", age = 20, score = 50.0)
        val result = modification(record)

        assertEquals("Bob", result.name)
        assertEquals(21, result.age)
        assertEquals(100.0, result.score)
    }

    @Test
    fun testWhitespaceHandling() {
        val modification = ModificationExpression.fromString<TestRecord>("  name   =   'Alice'  ").modification
        val record = TestRecord()
        val result = modification(record)

        assertEquals("Alice", result.name)
    }

    @Test
    fun testWhitespaceInChain() {
        val modification =
            ModificationExpression.fromString<TestRecord>("name = 'Alice'  ;  age = 25 ; active = true").modification
        val record = TestRecord()
        val result = modification(record)

        assertEquals("Alice", result.name)
        assertEquals(25, result.age)
        assertEquals(true, result.active)
    }

    @Test
    fun testEscapedQuotes() {
        val modification = ModificationExpression.fromString<TestRecord>("name = 'O\\'Brien'").modification
        val record = TestRecord()
        val result = modification(record)

        assertEquals("O'Brien", result.name)
    }

    // Error cases

    @Test
    fun testEmptyExpression() {
        assertFailsWith<ModificationParseException> {
            ModificationExpression.fromString<TestRecord>("")
        }
    }

    @Test
    fun testUnknownField() {
        val exception = assertFailsWith<ModificationParseException> {
            ModificationExpression.fromString<TestRecord>("unknownField = 'value'")
        }
        assert(exception.message!!.contains("Unknown field"))
        assert(exception.message!!.contains("Available fields"))
    }

    @Test
    fun testUnknownFieldWithSuggestion() {
        val exception = assertFailsWith<ModificationParseException> {
            ModificationExpression.fromString<TestRecord>("nam = 'value'")
        }
        assert(exception.message!!.contains("Unknown field 'nam'"))
        assert(exception.message!!.contains("Did you mean: name"))
    }

    @Test
    fun testMissingOperator() {
        assertFailsWith<ModificationParseException> {
            ModificationExpression.fromString<TestRecord>("name 'Alice'")
        }
    }

    @Test
    fun testUnterminatedString() {
        val exception = assertFailsWith<ModificationParseException> {
            ModificationExpression.fromString<TestRecord>("name = 'Alice")
        }
        assert(exception.message!!.contains("Unterminated string"))
    }

    @Test
    fun testUnquotedString() {
        val exception = assertFailsWith<ModificationParseException> {
            ModificationExpression.fromString<TestRecord>("name = Alice")
        }
        assert(exception.message!!.contains("strings must be quoted"))
    }

    @Test
    fun testInvalidOperator() {
        val exception = assertFailsWith<ModificationParseException> {
            ModificationExpression.fromString<TestRecord>("name == 'Alice'")
        }
        assert(exception.message!!.contains("Expected modification operator"))
    }

    @Test
    fun testMissingSemicolon() {
        val exception = assertFailsWith<ModificationParseException> {
            ModificationExpression.fromString<TestRecord>("name = 'Alice' age = 25")
        }
        assert(exception.message!!.contains("Expected ';'"))
    }

    @Test
    fun testIncrementStringField() {
        val exception = assertFailsWith<ModificationParseException> {
            ModificationExpression.fromString<TestRecord>("name += 5")
        }
        // String fields can use += but with string values, not numbers
        assert(exception.message!!.contains("Cannot append"))
    }

    @Test
    fun testAppendToIntField() {
        val exception = assertFailsWith<ModificationParseException> {
            ModificationExpression.fromString<TestRecord>("age += 'text'")
        }
        assert(exception.message!!.contains("Cannot increment") || exception.message!!.contains("non-numeric"))
    }

    @Test
    fun testDecrementStringField() {
        val exception = assertFailsWith<ModificationParseException> {
            ModificationExpression.fromString<TestRecord>("name -= 'text'")
        }
        assert(exception.message!!.contains("does not support -= operator"))
    }

    @Test
    fun testMultiplyStringField() {
        val exception = assertFailsWith<ModificationParseException> {
            ModificationExpression.fromString<TestRecord>("name *= 2")
        }
        assert(exception.message!!.contains("does not support *= operator"))
    }

    @Test
    fun testMultiplyBooleanField() {
        val exception = assertFailsWith<ModificationParseException> {
            ModificationExpression.fromString<TestRecord>("active *= 2")
        }
        assert(exception.message!!.contains("does not support *= operator"))
    }

    @Test
    fun testTrailingSemicolon() {
        val modification = ModificationExpression.fromString<TestRecord>("name = 'Alice';").modification
        val record = TestRecord()
        val result = modification(record)

        assertEquals("Alice", result.name)
    }

    @Test
    fun testMultipleTrailingSemicolons() {
        val exception = assertFailsWith<ModificationParseException> {
            ModificationExpression.fromString<TestRecord>("name = 'Alice';;")
        }
        assert(exception.message!!.contains("Expected field name"))
    }

    @Test
    fun testPrintExamples() {
        println("\n=== ModificationExpression Examples ===\n")

        val examples = listOf(
            "name = 'Alice'" to "Simple assignment",
            "age = 25" to "Integer assignment",
            "age += 5" to "Increment",
            "score -= 10.5" to "Decrement",
            "score *= 2" to "Multiply",
            "name += ' Jr.'" to "String append",
            "name = 'Alice'; age = 25" to "Multiple modifications",
            "name = 'Bob'; age += 1; score *= 2; active = true" to "Complex chain"
        )

        for ((expression, description) in examples) {
            println("$description:")
            println("  Expression: $expression")
            val modification = ModificationExpression.fromString<TestRecord>(expression).modification
            println("  Parsed: $modification")

            val record = TestRecord(name = "John", age = 20, score = 50.0, active = false)
            val result = modification(record)
            println("  Result: name='${result.name}', age=${result.age}, score=${result.score}, active=${result.active}")
            println()
        }
    }
}
