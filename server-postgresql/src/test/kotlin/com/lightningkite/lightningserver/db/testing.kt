package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.Condition
import com.lightningkite.lightningdb.condition
import com.lightningkite.lightningdb.eq
import com.lightningkite.lightningdb.insertOne
import com.lightningkite.lightningdb.test.*
import com.lightningkite.lightningserver.serialization.Serialization
import io.zonky.test.db.postgres.junit.EmbeddedPostgresRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.*
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

    @Test fun schema2() {
        val db = Database.connect(pg.embeddedPostgres.postgresDatabase)
        val collection = PostgresCollection(db, "LargeTestModel", LargeTestModel.serializer())
        runBlocking {
            // Quick test
            val t = LargeTestModel()
            collection.insertOne(t)
            assertEquals(t, collection.find(Condition.Always()).firstOrNull())
            assertEquals(t, collection.find(condition { it.byte eq 0 }).firstOrNull())
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


class PostgresAggregationsTest : AggregationsTest() {
    companion object {
        @ClassRule @JvmField val postgres = EmbeddedPostgresRules.singleInstance()
    }
    override val database: com.lightningkite.lightningdb.Database by lazy { PostgresDatabase(Database.connect(postgres.embeddedPostgres.postgresDatabase)) }
}

class PostgresConditionTests : ConditionTests() {
    companion object {
        @ClassRule @JvmField val postgres = EmbeddedPostgresRules.singleInstance()
    }
    override val database: com.lightningkite.lightningdb.Database by lazy { PostgresDatabase(Database.connect(postgres.embeddedPostgres.postgresDatabase)) }
}

class PostgresModificationTests : ModificationTests() {
    companion object {
        @ClassRule @JvmField val postgres = EmbeddedPostgresRules.singleInstance()
    }
    override val database: com.lightningkite.lightningdb.Database by lazy { PostgresDatabase(Database.connect(postgres.embeddedPostgres.postgresDatabase)) }
}

class PostgresSortTest : SortTest() {
    companion object {
        @ClassRule @JvmField val postgres = EmbeddedPostgresRules.singleInstance()
    }
    override val database: com.lightningkite.lightningdb.Database by lazy { PostgresDatabase(Database.connect(postgres.embeddedPostgres.postgresDatabase)) }
}

class PostgresMetaTest : MetaTest() {
    companion object {
        @ClassRule @JvmField val postgres = EmbeddedPostgresRules.singleInstance()
    }
    override val database: com.lightningkite.lightningdb.Database by lazy { PostgresDatabase(Database.connect(postgres.embeddedPostgres.postgresDatabase)) }
}