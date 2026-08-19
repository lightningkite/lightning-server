package com.lightningkite.lightningserver.testdata

import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.uuid.Uuid

class TestDataGenerationTests {
    @Test
    fun uuidSeeded() {
        for (gen in TestDataGeneration.iterate(50, Int.serializer(), "seed".hashCode())) {
            with(gen) { println(Uuid.seeded()) }
        }
    }
}