package com.lightningkite.lightningserver.schema

import com.lightningkite.lightningdb.test.LargeTestModel
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.jsonschema.schema
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertContains

class SchemaTest {
    @Test fun nestedEnum() {
        TestSettings
        println(lightningServerKSchema.enums)
        assertContains(lightningServerKSchema.enums.keys, "com.lightningkite.lightningdb.test.TestEnum")
    }
}