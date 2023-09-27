package com.lightningkite.lightningserver.schema

import com.lightningkite.lightningdb.test.LargeTestModel
import com.lightningkite.lightningserver.jsonschema.schema
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class SchemaTest {
    @Serializable data class Box<T>(val item: T)
//    data class
    @Test
    fun largeSchema() {
        Box.serializer(Int.serializer()).let {
            println(it.descriptor.serialName)
            println(it.descriptor::class.qualifiedName)
        }
        val schema = Serialization.json.schema(LargeTestModel.serializer())
        println(Json(Serialization.json) { prettyPrint = true }.encodeToString(schema))
    }
}