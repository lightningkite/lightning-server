package com.lightningkite.lightningserver.jsonschema

import com.lightningkite.lightningdb.test.HasServerFiles
import com.lightningkite.lightningdb.test.LargeTestModel
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class JsonSchemaTest {
    @Test
    fun test() {
        TestSettings
        val schema = Serialization.jsonWithoutDefaults.schema(HasServerFiles.serializer())
            .also(::println)
            .also { println(Json(Serialization.jsonWithoutDefaults) { prettyPrint = true }.encodeToString(it)) }
        assertEquals("#/definitions/com.lightningkite.lightningdb.ServerFile", schema.definitions["com.lightningkite.lightningdb.test.HasServerFiles"]!!.properties!!["file"]!!.ref)
    }
}