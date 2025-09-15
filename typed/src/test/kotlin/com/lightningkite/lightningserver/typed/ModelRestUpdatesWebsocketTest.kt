package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.GeneralServerSettings
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.runtime.test.TestRunner
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.runtime.test.sendWebSocketSubscriptionMessage
import com.lightningkite.lightningserver.runtime.send
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.lightningserver.websockets.text
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelRestUpdatesWebsocketTest {

    object TestServer : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val info = database.modelInfo<HasId<*>?, Sample, String>(
            auth = noAuth,
            permissions = { ModelPermissions.allowAll() }
        )
        val rest = path.path("model") include ModelRestEndpoints(info)
        val ws = path.path("model").path("updates") include ModelRestUpdatesWebsocket(info)
    }

    @Test
    fun initial_condition_and_general_topic_flow() = runBlocking {
        TestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val socket = TestServer.ws.websocket.test()
            val json = socket.server.externalSerialization.json

            // Send a condition from client: Always
            val cond: Condition<Sample> = Condition.Always
            val frameText = json.encodeToString(Condition.serializer(Sample.serializer()), cond)
            socket.send(WebSocketFrame.Text(frameText))

            // Expect initial echo of condition as CollectionUpdates(condition=...)
            var last: WebSocketFrame? = null
            socket.onMessageSent = { last = it }
            // The message was already sent by previous call; use messages flow to capture it synchronously
            // but onMessageSent after assignment will capture the next ones. So resend a no-op condition to get a predictable frame
            socket.send(WebSocketFrame.Text(frameText))
            val echoed = last as WebSocketFrame.Text
            val echoedUpdates = json.decodeFromString(CollectionUpdates.serializer(Sample.serializer(), String.serializer()), echoed.text)
            assertNotNull(echoedUpdates.condition)
            assertTrue(echoedUpdates.condition is Condition.Always)

            // Now simulate a change via the model info's change listener by inserting a model
            val inserted = Sample("1", "A")
            TestServer.info.collection().insert(listOf(inserted))

            val afterInsert = last as WebSocketFrame.Text
            val updates = json.decodeFromString(CollectionUpdates.serializer(Sample.serializer(), String.serializer()), afterInsert.text)
            assertEquals(setOf(inserted), updates.updates)
            assertEquals(emptySet<String>(), updates.remove)

            // Simulate a removal via direct topic send
            val removalChanges = CollectionChanges(listOf(EntryChange(old = inserted, new = null)))
            TestServer.ws.generalTopic.send(removalChanges)

            val afterRemove = last as WebSocketFrame.Text
            val removal = json.decodeFromString(CollectionUpdates.serializer(Sample.serializer(), String.serializer()), afterRemove.text)
            assertEquals(setOf(inserted._id), removal.remove)
        }
    }

    @Test
    fun no_updates_when_condition_never() = runBlocking {
        TestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val socket = TestServer.ws.websocket.test()
            val json = socket.server.externalSerialization.json

            var count = 0
            socket.onMessageSent = { count++ }

            val never: Condition<Sample> = Condition.Never
            val frameText = json.encodeToString(Condition.serializer(Sample.serializer()), never)
            socket.send(WebSocketFrame.Text(frameText))
            assertEquals(1, count) // initial echo

            // Send changes that should be filtered out entirely
            val changes = CollectionChanges(listOf(
                EntryChange(old = null, new = Sample("2", "X")),
                EntryChange(old = Sample("3", "Y"), new = null)
            ))
            TestServer.ws.generalTopic.send(changes)
            assertEquals(1, count) // still only the echo
        }
    }

    @Test
    fun overload_triggers_when_payload_large() = runBlocking {
        TestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val socket = TestServer.ws.websocket.test()
            val json = socket.server.externalSerialization.json

            val always: Condition<Sample> = Condition.Always
            val frameText = json.encodeToString(Condition.serializer(Sample.serializer()), always)
            socket.send(WebSocketFrame.Text(frameText))

            var last: WebSocketFrame? = null
            socket.onMessageSent = { last = it }

            val big = "x".repeat(5000)
            val changes = CollectionChanges((1..6).map { i -> EntryChange<Sample>(null, Sample(i.toString(), big)) })
            TestServer.ws.generalTopic.send(changes)

            val got = last as WebSocketFrame.Text
            val upd = json.decodeFromString(CollectionUpdates.serializer(Sample.serializer(), String.serializer()), got.text)
            assertTrue(upd.overload == true)
        }
    }
}

@Serializable
@GenerateDataClassPaths
data class Sample(override val _id: String, val name: String): HasId<String>