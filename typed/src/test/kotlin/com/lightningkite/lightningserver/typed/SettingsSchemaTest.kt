package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.Engine
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.typed.sdk.SDK
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsSchemaTest {
    object SchemaServer : ServerBuilder() {
        init {
            registerBasicMediaTypeCoders()
        }
        val publicUrl = setting("publicUrl", "http://localhost:8080")
        val secret = setting("secret", "changeme")
        val optionalThing = setting("optionalThing", "default", optional = true)
    }

    @Test
    fun generatesValidRootSchema() {
        val root: JsonObject = SDK.withDefaultRuntime(SchemaServer) {
            SchemaServer.settingsSchemaJson(contextOf<Engine>().internalSerializersModule)
        }

        // additionalProperties:false at root flags typo'd keys
        assertEquals(JsonPrimitive(false), root["additionalProperties"])
        assertEquals(JsonPrimitive("object"), root["type"])

        val properties = root["properties"]!!.jsonObject
        // One property per setting (plus the optional "defaults" key)
        assertTrue("publicUrl" in properties.keys)
        assertTrue("secret" in properties.keys)
        assertTrue("optionalThing" in properties.keys)
        assertTrue("defaults" in properties.keys)

        // Optional setting is not in required; non-optional ones are.
        val required = root["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertTrue("publicUrl" in required)
        assertTrue("secret" in required)
        assertFalse("optionalThing" in required, "optional setting must not be required")
    }
}
