@file:UseContextualSerialization(UUID::class)

package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.typed.test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChangeSocketTest {
    @Serializable
    @DatabaseModel
    data class TestThing(override val _id: UUID = UUID.randomUUID()) : HasId<UUID>

    @Test
    fun test() {
        val database = TestSettings.database
        runBlocking {
            TestSettings.ws.test {
                val initial = TestThing()
                database().collection<TestThing>().insertOne(initial)
                assertTrue(incoming.tryReceive().isFailure)
                this.send(Query())
                assertEquals(ListChange(wholeList = listOf(initial)), incoming.receive().also { println("Got $it") })
                val newThing = TestThing()
                database().collection<TestThing>().insertOne(newThing)
                assertEquals(ListChange(new = newThing), incoming.receive().also { println("Got $it") })
            }
        }
    }
}
