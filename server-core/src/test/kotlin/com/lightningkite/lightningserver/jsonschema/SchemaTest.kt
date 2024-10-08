@file:UseContextualSerialization(LocalDate::class, Instant::class, UUID::class, ServerFile::class)

package com.lightningkite.lightningserver.jsonschema

import com.lightningkite.prepareModelsServerCore
import com.lightningkite.lightningdb.*
import com.lightningkite.serialization.*
import com.lightningkite.lightningserver.files.ServerFile
import com.lightningkite.lightningserver.prepareModelsServerCoreTest
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.prepareModelsShared
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.properties.Properties
import kotlinx.serialization.properties.encodeToStringMap
import org.junit.Test
import java.net.URLEncoder
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import java.util.*
import com.lightningkite.UUID
import kotlin.test.assertTrue

class SchemaTest {
    init {

        prepareModelsShared()
        prepareModelsServerCore()
        prepareModelsServerCoreTest()
    }

    @Test
    fun quick() {
        val schema = Json.schema(Post.serializer())
        println(Serialization.jsonWithoutDefaults.encodeToString(schema))
    }

    @Test
    fun condition() {
        val schema = Json.schema(Condition.serializer(Post.serializer()))
        println(Serialization.jsonWithoutDefaults.encodeToString(schema))
    }

    @Test
    fun modification() {
        val schema = Json.schema(Modification.serializer(Post.serializer()))
        println(Serialization.jsonWithoutDefaults.encodeToString(schema))
    }

    @Test
    fun params() {
        println(
            Properties.encodeToStringMap(condition<Post> { (it.author eq "Bill") and (it.title eq "Bills Greatest") }).entries.joinToString(
                "&"
            ) { it.key + "=" + URLEncoder.encode(it.value, Charsets.UTF_8) })
    }

    @Serializable
    data class Acknowledgement(
        val test: UUID? = null
    )

    @Test
    fun nullableSchema() {
        val schema = Serialization.json.schema(Acknowledgement.serializer())
        println(Serialization.jsonWithoutDefaults.encodeToString(schema))
    }
}
