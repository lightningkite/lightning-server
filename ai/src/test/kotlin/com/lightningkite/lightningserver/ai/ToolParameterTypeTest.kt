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
}