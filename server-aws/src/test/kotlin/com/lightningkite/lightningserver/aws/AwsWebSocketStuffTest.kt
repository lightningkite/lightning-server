package com.lightningkite.lightningserver.aws

import com.lightningkite.lightningserver.db.embeddedDynamo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import kotlin.test.Test
import kotlin.test.assertEquals

class AwsWebSocketStuffTest {
    val d = embeddedDynamo()
    @Test fun basics(): Unit = runBlocking {
        with(AwsWebSocketDynamoDb(d, "basics")) {
            val testSocketA = "test-socket-id-A"
            val testSocketB = "test-socket-id-B"
            val testSocketC = "test-socket-id-C"
            setState(testSocketA, "A")
            setState(testSocketB, "B")
            setState(testSocketC, "C")
            println(debugStates())
            assertEquals(mapOf(testSocketA to "A", testSocketB to "B"), states(listOf(testSocketA, testSocketB)))
            subscribe("path", "topic", testSocketA)
            subscribe("path", "topic", testSocketB)
            subscribe("other-path", "topic", testSocketC)
            assertEquals(mapOf(
                "path" to setOf(testSocketA, testSocketB),
                "other-path" to setOf(testSocketC)
            ),subscribers("topic"))
            clean(testSocketA)
            assertEquals(mapOf(
                "path" to setOf(testSocketB),
                "other-path" to setOf(testSocketC)
            ),subscribers("topic"))
            assertTrue(testSocketA !in debugStates().keys)
            assertFalse(updateState(testSocketB, "wrong", "wronger"))
            assertTrue(updateState(testSocketB, "B", "B2"))
            assertFalse(updateState(testSocketB, "B", "B3"))
            assertTrue(updateState(testSocketB, "B2", "B3"))
            assertEquals("B3", state(testSocketB))
            assertEquals("B3", debugStates()[testSocketB])
            unsubscribe("topic", testSocketC)
            assertEquals(mapOf(
                "path" to setOf(testSocketB),
            ),subscribers("topic"))
        }
    }
}