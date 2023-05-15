package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.collectChunked
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

class TestFlowExtensions {

    @Test
    fun testCollectChunked():Unit = runBlocking {

        var flow: Flow<Int> = flowOf(*0.until(100).toList().toTypedArray())

        var count = 0
        var emmitted = mutableListOf<Int>()

        flow.collectChunked(10){items ->
            count++
            assertEquals(10, items.size)
            emmitted.addAll(items)
        }
        assertEquals(10, count)
        repeat(100){
            assertEquals(it, emmitted[it])
        }

        flow = flowOf(1,2,3)
        count = 0
        emmitted = mutableListOf()
        flow.collectChunked(10){items ->
            count++
            assertEquals(3, items.size)
            emmitted.addAll(items)
        }
        assertEquals(1, count)
        assertEquals(listOf(1,2,3), emmitted)
    }


}