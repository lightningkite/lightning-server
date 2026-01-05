package com.lightningkite.lightningserver.ai

import com.lightningkite.services.database.ConditionSerializer
import com.lightningkite.services.database.SortPart
import com.lightningkite.services.database.test.LargeTestModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.EmptySerializersModule
import org.junit.Assert.*
import kotlin.test.Test

class ToolParameterTypeTest {
    @Serializable data class Box<T>(val value: T)
    @Test
    fun test() {
        println(Box.serializer(ConditionExpression.serializer(TestModel.serializer())).asToolDescriptor("sample", "sample", EmptySerializersModule(), 2))
    }

    @Serializable
    data class QueryRequest<T>(
        val condition: ConditionExpression<T>,
        val orderBy: List<SortPart<T>>,
        val skip: Int = 0,
        val limit: Int = 10,
    )

    @Test
    fun `verify full query request tool descriptor is compact`() {
        // This simulates what QueryTableTool generates
        val serializer = QueryRequest.serializer(TestModel.serializer())
        val descriptor = serializer.asToolDescriptor(
            "query_test",
            "Query the test table",
            EmptySerializersModule(),
            maxDepth = 6
        )

        println("=== QueryRequest Tool Descriptor ===")
        println(descriptor)
        println()

        // Verify condition is a String type (not complex object)
        val conditionParam = descriptor.requiredParameters.find { it.name == "condition" }
        println("Condition type: ${conditionParam?.type}")
        assert(conditionParam?.type is ai.koog.agents.core.tools.ToolParameterType.String) {
            "Expected condition to be String type but was ${conditionParam?.type}"
        }

        // Verify orderBy is a List of Strings
        val orderByParam = descriptor.requiredParameters.find { it.name == "orderBy" }
        println("OrderBy type: ${orderByParam?.type}")
        val orderByListType = orderByParam?.type as? ai.koog.agents.core.tools.ToolParameterType.List
        assert(orderByListType?.itemsType is ai.koog.agents.core.tools.ToolParameterType.String) {
            "Expected orderBy to be List<String> but was ${orderByParam?.type}"
        }
    }
}