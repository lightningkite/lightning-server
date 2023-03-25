@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.SetOnce
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.engine.UnitTestEngine
import com.lightningkite.lightningserver.engine.engine
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.GeneralServerSettings
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.lightningserver.websocket.test
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.serialization.encodeToString
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChangeSocketTest {
    @Serializable
    @DatabaseModel
    data class TestThing(override val _id: UUID = UUID.randomUUID()): HasId<UUID>

    @Test fun test() {
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
