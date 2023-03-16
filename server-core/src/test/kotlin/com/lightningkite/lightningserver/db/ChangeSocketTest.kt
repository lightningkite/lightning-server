@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.SetOnce
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.GeneralServerSettings
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.lightningserver.websocket.test
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.serialization.encodeToString
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals

class ChangeSocketTest {
    @Serializable
    @DatabaseModel
    data class TestThing(override val _id: UUID = UUID.randomUUID()): HasId<UUID>

    @Test fun test() {
        val database = TestSettings.database
        val ws = ServerPath("test").restApiWebsocket<Unit, TestThing, UUID>(database, ModelInfo(
            getCollection = { database().collection() },
            forUser = { this }
        ))
        runBlocking {
            ws.test {
                println("Test started")
                this.send(Query())
                println("Query sent, waiting for item")
                assertEquals(withTimeout(1000L) { println("Waiting for item"); incoming.receive().also { println("Got $it") } }, ListChange(wholeList = listOf()))
                val newThing = TestThing()
                println("Sending update")
                val r = async {
                    delay(10)
                    database().collection<TestThing>().insertOne(newThing)
                }
                assertEquals(withTimeout(1000L) { println("Waiting for item"); incoming.receive().also { println("Got $it") } }, ListChange(new = newThing))
                r.await()
            }
        }
    }
}
