package com.lightningkite.lightningdb.test

import com.lightningkite.lightningdb.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import com.lightningkite.now
import org.junit.Before
import org.junit.Test
import kotlinx.datetime.Instant
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

abstract class SortTest {

    init { prepareModels() }
    abstract val database: Database

    @Test
    fun testSortInt():Unit = runBlocking{
        val collection = database.collection<LargeTestModel>("SortTest_testSortInt")
        val items = listOf(
            LargeTestModel(int = 4),
            LargeTestModel(int = 5),
            LargeTestModel(int = 1),
            LargeTestModel(int = 2),
            LargeTestModel(int = 6),
            LargeTestModel(int = 3),
        )
        val sortedPosts = items.sortedBy { it.int }
        val reversePosts = items.sortedByDescending { it.int }
        collection.insertMany(items)
        val results1 = collection.find(Condition.Always()).toList()
        val results2 = collection.find(Condition.Always(), orderBy = listOf(SortPart(path<LargeTestModel>().int, true))).toList()
        val results3 = collection.find(Condition.Always(), orderBy = listOf(SortPart(path<LargeTestModel>().int, false))).toList()
        assertEquals(items.map { it._id }, results1.map { it._id })
        assertEquals(sortedPosts.map { it._id }, results2.map { it._id })
        assertEquals(reversePosts.map { it._id }, results3.map { it._id })
    }

    @Test
    fun testSortIntEmbedded():Unit = runBlocking{
        val collection = database.collection<LargeTestModel>("SortTest_testSortIntEmbedded")
        val items = listOf(
            LargeTestModel(embedded = ClassUsedForEmbedding(value2 = 4)),
            LargeTestModel(embedded = ClassUsedForEmbedding(value2 = 5)),
            LargeTestModel(embedded = ClassUsedForEmbedding(value2 = 1)),
            LargeTestModel(embedded = ClassUsedForEmbedding(value2 = 2)),
            LargeTestModel(embedded = ClassUsedForEmbedding(value2 = 6)),
            LargeTestModel(embedded = ClassUsedForEmbedding(value2 = 3)),
        )
        val sortedPosts = items.sortedBy { it.embedded.value2 }
        val reversePosts = items.sortedByDescending { it.embedded.value2 }
        collection.insertMany(items)
        val results1 = collection.find(Condition.Always()).toList()
        val results2 = collection.find(Condition.Always(), orderBy = listOf(SortPart(path<LargeTestModel>().embedded.value2, true))).toList()
        val results3 = collection.find(Condition.Always(), orderBy = listOf(SortPart(path<LargeTestModel>().embedded.value2, false))).toList()
        assertEquals(items.map { it._id }, results1.map { it._id })
        assertEquals(sortedPosts.map { it._id }, results2.map { it._id })
        assertEquals(reversePosts.map { it._id }, results3.map { it._id })
    }

    @Test
    fun testSortIntEmbeddedNullable():Unit = runBlocking{
        val collection = database.collection<LargeTestModel>("SortTest_testSortIntEmbeddedNullable")
        val items = listOf(
            LargeTestModel(embeddedNullable = ClassUsedForEmbedding(value2 = 4)),
            LargeTestModel(embeddedNullable = ClassUsedForEmbedding(value2 = 5)),
            LargeTestModel(embeddedNullable = ClassUsedForEmbedding(value2 = 1)),
            LargeTestModel(embeddedNullable = ClassUsedForEmbedding(value2 = 2)),
            LargeTestModel(embeddedNullable = ClassUsedForEmbedding(value2 = 6)),
            LargeTestModel(embeddedNullable = ClassUsedForEmbedding(value2 = 3)),
        )
        val sortedPosts = items.sortedBy { it.embeddedNullable?.value2 }
        val reversePosts = items.sortedByDescending { it.embeddedNullable?.value2 }
        collection.insertMany(items)
        val results1 = collection.find(Condition.Always()).toList()
        val results2 = collection.find(Condition.Always(), orderBy = listOf(SortPart(path<LargeTestModel>().embeddedNullable.notNull.value2, true))).toList()
        val results3 = collection.find(Condition.Always(), orderBy = listOf(SortPart(path<LargeTestModel>().embeddedNullable.notNull.value2, false))).toList()
        assertEquals(items.map { it._id }, results1.map { it._id })
        assertEquals(sortedPosts.map { it._id }, results2.map { it._id })
        assertEquals(reversePosts.map { it._id }, results3.map { it._id })
    }

    @Test
    fun testSortTime():Unit = runBlocking{
        val collection = database.collection<LargeTestModel>("SortTest_testSortTime")
        val items = listOf(
            LargeTestModel(instant = now().minus(4.minutes)),
            LargeTestModel(instant = now().minus(5.minutes)),
            LargeTestModel(instant = now()),
            LargeTestModel(instant = now().minus(2.minutes)),
            LargeTestModel(instant = now().minus(6.minutes)),
            LargeTestModel(instant = now().minus(3.minutes)),
        )
        val sortedPosts = items.sortedBy { it.instant }
        val reversePosts = items.sortedByDescending { it.instant }
        collection.insertMany(items)
        val results1 = collection.find(Condition.Always()).toList()
        val results2 = collection.find(Condition.Always(), orderBy = listOf(SortPart(path<LargeTestModel>().instant, true))).toList()
        val results3 = collection.find(Condition.Always(), orderBy = listOf(SortPart(path<LargeTestModel>().instant, false))).toList()
        assertEquals(items.map { it._id }, results1.map { it._id })
        assertEquals(sortedPosts.map { it._id }, results2.map { it._id })
        assertEquals(reversePosts.map { it._id }, results3.map { it._id })
    }

    @Test
    fun testSortCase():Unit = runBlocking{
        val collection = database.collection<LargeTestModel>("SortTest_testSortCase")
        val items = listOf(
            LargeTestModel(string = "aa"),
            LargeTestModel(string = "Ab"),
            LargeTestModel(string = "ac"),
            LargeTestModel(string = "Ad"),
            LargeTestModel(string = "ae"),
            LargeTestModel(string = "Af"),
        )
        val sortedPosts = items.sortedBy { it.string }
        val reversePosts = items.sortedByDescending { it.string }
        collection.insertMany(items)
        val results1 = collection.find(Condition.Always()).toList()
        val results2 = collection.find(Condition.Always(), orderBy = listOf(SortPart(path<LargeTestModel>().string, true))).toList()
        val results3 = collection.find(Condition.Always(), orderBy = listOf(SortPart(path<LargeTestModel>().string, false))).toList()
        assertEquals(items.map { it._id }, results1.map { it._id })
        assertEquals(sortedPosts.map { it._id }, results2.map { it._id })
        assertEquals(reversePosts.map { it._id }, results3.map { it._id })
    }

    @Test
    fun testSortCaseInsensitive():Unit = runBlocking{
        val collection = database.collection<LargeTestModel>("SortTest_testSortCaseInsensitive")
        val items = listOf(
            LargeTestModel(string = "aa"),
            LargeTestModel(string = "Ab"),
            LargeTestModel(string = "ac"),
            LargeTestModel(string = "Ad"),
            LargeTestModel(string = "ae"),
            LargeTestModel(string = "Af"),
        )
        val sortedPosts = items.sortedBy { it.string.lowercase() }
        val reversePosts = items.sortedByDescending { it.string.lowercase() }
        collection.insertMany(items)
        val results1 = collection.find(Condition.Always()).toList()
        val results2 = collection.find(Condition.Always(), orderBy = listOf(SortPart(path<LargeTestModel>().string, true, true))).toList()
        val results3 = collection.find(Condition.Always(), orderBy = listOf(SortPart(path<LargeTestModel>().string, false, true))).toList()
        assertEquals(items.map { it._id }, results1.map { it._id })
        assertEquals(sortedPosts.map { it._id }, results2.map { it._id })
        assertEquals(reversePosts.map { it._id }, results3.map { it._id })
    }


    @Test
    fun testSortCaseInsensitiveCrash():Unit = runBlocking{
        val collection = database.collection<LargeTestModel>("SortTest_testSortCaseInsensitive")
        val items = listOf(
            LargeTestModel(string = "aa"),
            LargeTestModel(string = "Ab"),
            LargeTestModel(string = "ac"),
            LargeTestModel(string = "Ad"),
            LargeTestModel(string = "ae"),
            LargeTestModel(string = "Af"),
        )
        collection.find(Condition.Always(), orderBy = listOf(SortPart(path<LargeTestModel>().long, true, true))).toList()
    }

}