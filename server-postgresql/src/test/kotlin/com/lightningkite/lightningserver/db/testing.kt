package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.test.ClassUsedForEmbedding
import com.lightningkite.lightningdb.test.EmbeddedNullable
import com.lightningkite.lightningdb.test.LargeTestModel
import com.lightningkite.lightningserver.serialization.Serialization
import io.zonky.test.db.postgres.junit.EmbeddedPostgresRules
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.*
import kotlin.collections.LinkedHashMap
import kotlin.test.assertEquals

class BasicTest() {
    @Rule
    @JvmField
    val pg = EmbeddedPostgresRules.singleInstance()

    @Test
    fun schema() {
        val table = SerialDescriptorTable(LargeTestModel.serializer().descriptor)
        val db = Database.connect(pg.embeddedPostgres.postgresDatabase)
        transaction(db) {
            println(table.columns.joinToString { it.name })
            addLogger(StdOutSqlLogger)
            SchemaUtils.createMissingTablesAndColumns(table)
            val format = DbMapLikeFormat()
            val toInsert = LargeTestModel()
            table.insert {
                format.encode(LargeTestModel.serializer(), toInsert, it)
            }
            val results = table.selectAll().map {
                format.decode(LargeTestModel.serializer(), it)
            }.onEach { println(it) }
            assertEquals(toInsert, results[0])
        }
    }
}

class CodingTest() {
    @Serializable
    data class TestModel(
        @Contextual val uuid: UUID = UUID.randomUUID(),
        @Contextual val time: Instant,
        @Contextual val timeZoned: ZonedDateTime,
        val x: String?,
        val y: Int,
        val z: ClassUsedForEmbedding?,
        val array: List<Int>,
        val embArray: List<ClassUsedForEmbedding>,
        val nested: List<List<Int>> ,
        val map: Map<String, Int>,
    )

    @Test
    fun quick() {
        val out = LinkedHashMap<String, Any?>()
        val format = DbMapLikeFormat()
        format.encode(
            TestModel.serializer(),
            TestModel(
                time = Instant.now(),
                timeZoned = ZonedDateTime.now(),
                x = "test",
                y = 1,
                z = ClassUsedForEmbedding("def", 2),
                array = listOf(1, 2, 3),
                embArray = listOf(
                    ClassUsedForEmbedding("a", 3),
                    ClassUsedForEmbedding("b", 4),
                ),
                nested = listOf(listOf(1, 2), listOf(3, 4)),
                map = mapOf("one" to 1, "two" to 2)
            ),
            out
        )
        println(out)
        println(out.mapValues { it.value?.let { it::class.simpleName } ?: "NULL" })
        println(format.decode(TestModel.serializer(), out))
    }

    @Test
    fun large() {
        val out = LinkedHashMap<String, Any?>()
        val format = DbMapLikeFormat()
        format.encode(
            LargeTestModel.serializer(),
            LargeTestModel(),
            out
        )
        println(out)
        println(out.mapValues { it.value?.let { it::class.simpleName } ?: "NULL" })
        println(format.decode(LargeTestModel.serializer(), out))
    }
}