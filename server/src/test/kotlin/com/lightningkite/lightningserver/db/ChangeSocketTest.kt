@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.SetOnce
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
        SetOnce.allowOverwrite {
            GeneralServerSettings()
            DatabaseSettings(url = "ram")
        }
        val db = database.collection<TestThing>() as AbstractSignalFieldCollection<TestThing>
        val ws = ServerPath("test").restApiWebsocket(db) { user: Unit -> this }
        runBlocking {
            ws.test {
                this.send(Query())
                assertEquals(withTimeout(100L) { incoming.receive() }, ListChange(wholeList = listOf()))
                val newThing = TestThing()
                db.insertOne(newThing)
                assertEquals(withTimeout(100L) { incoming.receive() }, ListChange(new = newThing))
            }
        }
    }
}