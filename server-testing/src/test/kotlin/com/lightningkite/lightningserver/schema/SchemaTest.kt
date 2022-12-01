package com.lightningkite.lightningdb.test.com.lightningkite.lightningserver.schema

import com.lightningkite.lightningdb.test.LargeTestModel
import com.lightningkite.lightningserver.jsonschema.humanize
import com.lightningkite.lightningserver.jsonschema.schema
import com.lightningkite.lightningserver.jsonschema.schemaDefinitions
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class SchemaTest {
//    @Serializable
//    data class
    @Test
    fun largeSchema() {
        val schema = Serialization.json.schema(LargeTestModel.serializer())
        println(Json(Serialization.json) { prettyPrint = true }.encodeToString(schema))
    }

    @Test fun humanize() {
        println("TestThing_isCool".humanize())
    }
}