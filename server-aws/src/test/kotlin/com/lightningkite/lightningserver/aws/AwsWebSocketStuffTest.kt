package com.lightningkite.lightningserver.aws

import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.db.embeddedDynamo
import com.lightningkite.lightningserver.serialization.InternalCommunicationEncoding
import com.lightningkite.lightningserver.websocket.WebSocketConnectRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

class AwsWebSocketStuffTest {
    val d = embeddedDynamo()

    @Ignore("Works locally but fails in CI")
    @Test
    fun basics(): Unit = runBlocking {
        with(AwsWebSocketDynamoDb(d, "basics", encoding = InternalCommunicationEncoding.JavaData)) {
            val testSocketA = "test-socket-id-A"
            val testSocketB = "test-socket-id-B"
            val testSocketC = "test-socket-id-C"
            setState(testSocketA, WebSocketConnectRequest(ServerPath.root, mapOf()), "A".toByteArray())
            setState(testSocketB, WebSocketConnectRequest(ServerPath.root, mapOf()), "B".toByteArray())
            setState(testSocketC, WebSocketConnectRequest(ServerPath.root, mapOf()), "C".toByteArray())
            println(debugStates())
            assertEquals(
                mapOf(testSocketA to "A", testSocketB to "B"),
                states(listOf(testSocketA, testSocketB)).mapValues { it.value.state.toString(Charsets.UTF_8) })
            subscribe("path", "topic", testSocketA)
            subscribe("path", "topic", testSocketB)
            subscribe("other-path", "topic", testSocketC)
            assertEquals(
                mapOf(
                    "path" to setOf(testSocketA, testSocketB),
                    "other-path" to setOf(testSocketC)
                ), subscribers("topic")
            )
            clean(testSocketA)
            assertEquals(
                mapOf(
                    "path" to setOf(testSocketB),
                    "other-path" to setOf(testSocketC)
                ), subscribers("topic")
            )
            assertTrue(testSocketA !in debugStates().keys)
            assertFalse(updateState(testSocketB, "wrong".toByteArray(), "wronger".toByteArray()))
            assertTrue(updateState(testSocketB, "B".toByteArray(), "B2".toByteArray()))
            assertFalse(updateState(testSocketB, "B".toByteArray(), "B3".toByteArray()))
            assertTrue(updateState(testSocketB, "B2".toByteArray(), "B3".toByteArray()))
            assertArrayEquals("B3".toByteArray(), state(testSocketB)?.state)
            assertArrayEquals("B3".toByteArray(), debugStates()[testSocketB]?.state)
            unsubscribe("topic", testSocketC)
            assertEquals(
                mapOf(
                    "path" to setOf(testSocketB),
                ), subscribers("topic")
            )
        }
    }
}