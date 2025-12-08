package com.lightningkite.lightningserver.ai

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.uuid.Uuid

@Serializable
@GenerateDataClassPaths
data class TestModel(
    override val _id: Uuid = Uuid.random(),
    val name: String,
    val age: Int,
    val role: String,
    val active: Boolean
) : HasId<Uuid>

class ConditionSerializationTest {

    private val prettyJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    @Test
    fun testSimpleConditionSerialization() {
        println("=== Testing Condition Serialization ===\n")

        // Test 1: Simple equality using OnField
        println("1. Simple Equality (role == 'admin'):")
        val roleProperty = TestModel.serializer().serializableProperties!!.find { it.name == "role" }!!
        @Suppress("UNCHECKED_CAST")
        val condition1: Condition<TestModel> = Condition.OnField(
            roleProperty as SerializableProperty<TestModel, String>,
            Condition.Equal("admin")
        )
        val json1 = prettyJson.encodeToString(condition1)
        println(json1)
        println()

        // Test 2: Greater than
        println("2. Greater Than (age > 18):")
        val ageProperty = TestModel.serializer().serializableProperties!!.find { it.name == "age" }!!
        @Suppress("UNCHECKED_CAST")
        val condition2: Condition<TestModel> = Condition.OnField(
            ageProperty as SerializableProperty<TestModel, Int>,
            Condition.GreaterThan(18)
        )
        val json2 = prettyJson.encodeToString(condition2)
        println(json2)
        println()

        // Test 3: AND condition
        println("3. AND Condition (role == 'admin' AND active == true):")
        val activeProperty = TestModel.serializer().serializableProperties!!.find { it.name == "active" }!!

        @Suppress("UNCHECKED_CAST")
        val condition3: Condition<TestModel> = Condition.And(
            listOf(
                Condition.OnField(roleProperty as SerializableProperty<TestModel, String>, Condition.Equal("admin")),
                Condition.OnField(activeProperty as SerializableProperty<TestModel, Boolean>, Condition.Equal(true))
            )
        )
        val json3 = prettyJson.encodeToString(condition3)
        println(json3)
        println()

        // Test 4: String contains
        println("4. String Contains (name contains 'Alice'):")
        val nameProperty = TestModel.serializer().serializableProperties!!.find { it.name == "name" }!!
        @Suppress("UNCHECKED_CAST")
        val condition4: Condition<TestModel> = Condition.OnField(
            nameProperty as SerializableProperty<TestModel, String>,
            Condition.StringContains("Alice", ignoreCase = true)
        )
        val json4 = prettyJson.encodeToString(condition4)
        println(json4)
        println()

        // Test 5: Always (match all)
        println("5. Always (match all):")
        val condition5: Condition<TestModel> = Condition.Always
        val json5 = prettyJson.encodeToString(condition5)
        println(json5)
        println()

        // Now try to deserialize one back
        println("=== Testing Deserialization ===\n")
        println("6. Deserialize the AND condition back:")
        val deserialized = Json.decodeFromString<Condition<TestModel>>(json3)
        println("Deserialized successfully: ${deserialized::class.simpleName}")
        println()

        println("=== Comparison with Custom Format ===\n")
        println("Custom format for 'role == admin':")
        println("""{"field": "role", "operator": "equals", "value": "admin"}""")
        println()
        println("Actual Condition format for 'role == admin':")
        println(json1)
    }

    @Test
    fun testComplexCondition() {
        println("\n=== Complex Query Example ===\n")

        println("Query: Find users where (role == 'admin' OR role == 'moderator') AND active == true\n")

        val roleProperty = TestModel.serializer().serializableProperties!!.find { it.name == "role" }!!
        val activeProperty = TestModel.serializer().serializableProperties!!.find { it.name == "active" }!!

        @Suppress("UNCHECKED_CAST")
        val condition: Condition<TestModel> = Condition.And(
            listOf(
                Condition.Or(
                    listOf(
                        Condition.OnField(roleProperty as SerializableProperty<TestModel, String>, Condition.Equal("admin")),
                        Condition.OnField(roleProperty as SerializableProperty<TestModel, String>, Condition.Equal("moderator"))
                    )
                ),
                Condition.OnField(activeProperty as SerializableProperty<TestModel, Boolean>, Condition.Equal(true))
            )
        )

        val json = prettyJson.encodeToString(condition)
        println("Serialized Condition:")
        println(json)
    }
}
