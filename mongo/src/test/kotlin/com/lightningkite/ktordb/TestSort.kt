package com.lightningkite.ktordb

import com.lightningkite.ktordb.application.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.test.assertEquals

class TestSort: MongoTest() {

    @Before
    fun setup():Unit = runBlocking {
        LargeTestModel.mongo.deleteMany(Condition.Always())
    }

    @Test
    fun testSortInt():Unit = runBlocking{
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
        LargeTestModel.mongo.insertMany(items)
        val results1 = LargeTestModel.mongo.find(Condition.Always()).toList()
        val results2 = LargeTestModel.mongo.find(Condition.Always(), orderBy = listOf(SortPart(LargeTestModel::int, true))).toList()
        val results3 = LargeTestModel.mongo.find(Condition.Always(), orderBy = listOf(SortPart(LargeTestModel::int, false))).toList()
        assertEquals(items.map { it._id }, results1.map { it._id })
        assertEquals(sortedPosts.map { it._id }, results2.map { it._id })
        assertEquals(reversePosts.map { it._id }, results3.map { it._id })

    }

    @Test
    fun testSortTime():Unit = runBlocking{
        val items = listOf(
            LargeTestModel(instant = Instant.now().minus(4, ChronoUnit.MINUTES)),
            LargeTestModel(instant = Instant.now().minus(5, ChronoUnit.MINUTES)),
            LargeTestModel(instant = Instant.now()),
            LargeTestModel(instant = Instant.now().minus(2, ChronoUnit.MINUTES)),
            LargeTestModel(instant = Instant.now().minus(6, ChronoUnit.MINUTES)),
            LargeTestModel(instant = Instant.now().minus(3, ChronoUnit.MINUTES)),
        )
        val sortedPosts = items.sortedBy { it.instant }
        val reversePosts = items.sortedByDescending { it.instant }
        LargeTestModel.mongo.insertMany(items)
        val results1 = LargeTestModel.mongo.find(Condition.Always()).toList()
        val results2 = LargeTestModel.mongo.find(Condition.Always(), orderBy = listOf(SortPart(LargeTestModel::instant, true))).toList()
        val results3 = LargeTestModel.mongo.find(Condition.Always(), orderBy = listOf(SortPart(LargeTestModel::instant, false))).toList()
        assertEquals(items.map { it._id }, results1.map { it._id })
        assertEquals(sortedPosts.map { it._id }, results2.map { it._id })
        assertEquals(reversePosts.map { it._id }, results3.map { it._id })

    }

}