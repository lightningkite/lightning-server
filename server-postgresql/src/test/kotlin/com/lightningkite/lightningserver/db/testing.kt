package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.serialization.*
import com.lightningkite.lightningdb.test.*
import io.zonky.test.db.postgres.junit.EmbeddedPostgresRules
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import com.lightningkite.now
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.junit.*
import org.postgresql.util.PGobject
import java.sql.ResultSet
import kotlinx.datetime.Instant
import java.util.*
import kotlin.collections.LinkedHashMap
import kotlin.test.assertEquals
import com.lightningkite.UUID
import com.lightningkite.uuid

class BasicTest() {
    @Rule
    @JvmField
    val pg = EmbeddedPostgresRules.singleInstance()

    @Test fun schema2() {
        prepareLargeTestModelFields()
        val db = Database.connect(pg.embeddedPostgres.postgresDatabase)
        val collection = PostgresCollection(db, "LargeTestModel", LargeTestModel.serializer())
        runBlocking {
            // Quick test
            val t = LargeTestModel()
            collection.insertOne(t)
            assertEquals(t, collection.find(Condition.Always()).firstOrNull())
            assertEquals(t, collection.find(condition { it.byte eq 0 }).firstOrNull())
            assertEquals(t.byte, collection.updateOne(Condition.Always(), modification { it.byte += 1 }).old?.byte)
            assertEquals(t.byte.plus(1).toByte(), collection.updateOne(Condition.Always(), modification { it.byte += 1 }).old?.byte)
            assertEquals(t.byte.plus(2).toByte(), collection.updateOne(Condition.Always(), modification { it.byte += 1 }).old?.byte)
            assertEquals(t.byte.plus(3).toByte(), collection.updateOne(Condition.Always(), modification { it.byte += 1 }).old?.byte)
        }
    }

    @Test fun schema3() {
        val db = Database.connect(pg.embeddedPostgres.postgresDatabase)
        runBlocking {
            newSuspendedTransaction(db = db) {
                exec("""
                    CREATE TYPE inventory_item AS (
                       name text,
                       supplier_id integer,
                       price numeric
                    )
                """.trimIndent())
                exec("""
                    CREATE TABLE on_hand (
                       item inventory_item,
                       count integer
                    )
                """.trimIndent())
                exec("""
                    INSERT INTO on_hand VALUES (ROW('fuzzy dice', 42, 1.99), 1000)
                """.trimIndent())
                TestTable.selectAll().forEach {
                    println(it)
                    println(it[TestTable.item])
                }
            }
        }
    }
    class InventoryItemType(): ColumnType() {
        override fun sqlType(): String = "inventory_item"
        override fun nonNullValueToString(value: Any): String {
            println("Value: $value, type: ${value::class.qualifiedName}")
            (value as? PGobject)?.let { println(it.type); println(it.value) }
            return super.nonNullValueToString(value)
        }

        override fun notNullValueToDB(value: Any): Any {
            println("Value: $value, type: ${value::class.qualifiedName}")
            (value as? PGobject)?.let { println(it.type); println(it.value) }
            return super.notNullValueToDB(value)
        }

        override fun readObject(rs: ResultSet, index: Int): Any? {
            return super.readObject(rs, index)
        }

        override fun valueFromDB(value: Any): Any {
            println("Value: $value, type: ${value::class.qualifiedName}")
            (value as? PGobject)?.let { println(it.type); println(it.value) }
            return super.valueFromDB(value)
        }

        override fun valueToDB(value: Any?): Any? {
            println("Value: $value, type: ${value?.let { it::class.qualifiedName}}")
            (value as? PGobject)?.let { println(it.type); println(it.value) }
            return super.valueToDB(value)
        }

        override fun valueToString(value: Any?): String {
            println("Value: $value, type: ${value?.let { it::class.qualifiedName}}")
            (value as? PGobject)?.let { println(it.type); println(it.value) }
            return super.valueToString(value)
        }
    }
    object TestTable: Table("on_hand") {
        val item = registerColumn<Any?>("item", InventoryItemType())
        val count = integer("count")
    }
}

class CodingTest() {
    @Serializable
    data class TestModel(
        @Contextual val uuid: UUID = uuid(),
        @Contextual val time: Instant,
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
                time = now(),
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
    override val database: com.lightningkite.lightningdb.Database by lazy { PostgresDatabase{Database.connect(postgres.embeddedPostgres.postgresDatabase)} }
}

class PostgresConditionTests : ConditionTests() {
    companion object {
        @ClassRule @JvmField val postgres = EmbeddedPostgresRules.singleInstance()
    }
    override val database: com.lightningkite.lightningdb.Database by lazy { PostgresDatabase{Database.connect(postgres.embeddedPostgres.postgresDatabase)} }

    override fun test_geodistance_1() {
        println("Suppressed until this is supported")
    }

    override fun test_geodistance_2() {
        println("Suppressed until this is supported")
    }
}

class PostgresModificationTests : ModificationTests() {
    companion object {
        @ClassRule @JvmField val postgres = EmbeddedPostgresRules.singleInstance()
    }
    override val database: com.lightningkite.lightningdb.Database by lazy { PostgresDatabase { Database.connect(postgres.embeddedPostgres.postgresDatabase) } }
    override fun test_Map_modifyField() {
        // TODO: Make it work
    }

    override fun test_Map_setField() {
        // TODO: Make it work
    }

    override fun test_Map_unsetField() {
        // TODO: Make it work
    }
}

class PostgresSortTest : SortTest() {
    companion object {
        @ClassRule @JvmField val postgres = EmbeddedPostgresRules.singleInstance()
    }
    override val database: com.lightningkite.lightningdb.Database by lazy { PostgresDatabase{Database.connect(postgres.embeddedPostgres.postgresDatabase)} }
}
