package com.lightningkite.lightningserver.ai

import com.lightningkite.services.database.ConditionSerializer
import com.lightningkite.services.database.test.LargeTestModel
import kotlinx.serialization.modules.EmptySerializersModule
import org.junit.Assert.*
import kotlin.test.Test

class ToolParameterTypeTest {
    @Test
    fun test() {
        println(ConditionSerializer(LargeTestModel.serializer()).asToolDescriptor("sample", "sample", EmptySerializersModule(), 2))
    }
}