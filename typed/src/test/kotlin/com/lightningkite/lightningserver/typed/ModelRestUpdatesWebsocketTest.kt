package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.GeneralServerSettings
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.runtime.send
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.lightningserver.websockets.text
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.*

class ModelRestUpdatesWebSocketTest {

    object TestServer : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val info = database.modelInfo<HasId<*>?, Sample, String>(
            tableName = "Sample",
            auth = noAuth,
            permissions = { ModelPermissions.allowAll() }
        )
        val rest = path.path("model") include ModelRestEndpoints(info)
        val ws = path.path("model").path("updates") include ModelRestUpdatesWebSocket(info)

        init {
            registerBasicMediaTypeCoders()
        }
    }

    @Test
    fun initial_condition_and_general_topic_flow() = runBlocking {
        TestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val socket = TestServer.ws.webSocket.test()
            val json = contextOf<ServerRuntime>().externalSerialization.json

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
            val echoedUpdates = json.decodeFromString(
                CollectionUpdates.serializer(Sample.serializer(), String.serializer()),
                echoed.text
            )
            assertNotNull(echoedUpdates.condition)
            assertTrue(echoedUpdates.condition is Condition.Always)

            // Now simulate a change via the model info's change listener by inserting a model
            val inserted = Sample("1", "A")
            TestServer.info.table().insert(listOf(inserted))

            val afterInsert = last as WebSocketFrame.Text
            val updates = json.decodeFromString(
                CollectionUpdates.serializer(Sample.serializer(), String.serializer()),
                afterInsert.text
            )
            assertEquals(setOf(inserted), updates.updates)
            assertEquals(emptySet<String>(), updates.remove)

            // Simulate a removal via direct topic send
            val removalChanges = CollectionChanges(listOf(EntryChange(old = inserted, new = null)))
            TestServer.ws.generalTopic.send(removalChanges)

            val afterRemove = last as WebSocketFrame.Text
            val removal = json.decodeFromString(
                CollectionUpdates.serializer(Sample.serializer(), String.serializer()),
                afterRemove.text
            )
            assertEquals(setOf(inserted._id), removal.remove)
        }
    }

    @Test
    fun no_updates_when_condition_never() = runBlocking {
        TestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val socket = TestServer.ws.webSocket.test()
            val json = contextOf<ServerRuntime>().externalSerialization.json

            var count = 0
            socket.onMessageSent = { count++ }

            val never: Condition<Sample> = Condition.Never
            val frameText = json.encodeToString(Condition.serializer(Sample.serializer()), never)
            socket.send(WebSocketFrame.Text(frameText))
            assertEquals(1, count) // initial echo

            // Send changes that should be filtered out entirely
            val changes = CollectionChanges(
                listOf(
                    EntryChange(old = null, new = Sample("2", "X")),
                    EntryChange(old = Sample("3", "Y"), new = null)
                )
            )
            TestServer.ws.generalTopic.send(changes)
            assertEquals(1, count) // still only the echo
        }
    }

    object KeyedServer : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val info = database.modelInfo<HasId<*>?, Sample, String>(
            tableName = "Sample",
            auth = noAuth,
            permissions = { ModelPermissions.allowAll() }
        )
        val ws = path.path("keyed").path("updates") include ModelRestUpdatesWebSocket(info, Sample_name)

        init {
            registerBasicMediaTypeCoders()
        }
    }

    /**
     * Regression test for the hash-topic fan-out. The publisher used to iterate its hash list without
     * deduplicating it, so an N-row change whose rows share a key value published 2N messages (an old
     * and a new hash per row) and each one carried the whole matching change set -- O(N^2) bytes out of
     * a single database write. One grouped publish is the correct behaviour.
     */
    @Test
    fun mass_update_sharing_a_key_publishes_once() = runBlocking {
        KeyedServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val rowCount = 5
            KeyedServer.info.table().insert(
                (1..rowCount).map { Sample(_id = it.toString(), name = "shared", note = "before") }
            )

            val socket = KeyedServer.ws.webSocket.test()
            val json = contextOf<ServerRuntime>().externalSerialization.json

            // An Equal condition on the key is what lets the server shard onto the hash topic.
            val narrowed: Condition<Sample> = condition { it.name eq "shared" }
            socket.send(
                WebSocketFrame.Text(json.encodeToString(Condition.serializer(Sample.serializer()), narrowed))
            )

            var frames = 0
            socket.onMessageSent = { frames++ }

            // One database write touching every row, all of which share the key value "shared".
            KeyedServer.info.table().updateMany(
                condition { it.name eq "shared" },
                modification { it.note assign "after" }
            )

            assertEquals(
                1,
                frames,
                "A single mass update should publish once, not once per changed row"
            )
        }
    }

    /**
     * The other shape of oversized payload: not many rows, but a few enormous ones. A size estimate
     * derived from the descriptor cannot see this, because it has to assume a fixed size per string.
     */
    @Test
    fun overload_triggers_for_a_few_very_large_rows() = runBlocking {
        TestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val socket = TestServer.ws.webSocket.test()
            val json = contextOf<ServerRuntime>().externalSerialization.json

            val always: Condition<Sample> = Condition.Always
            socket.send(WebSocketFrame.Text(json.encodeToString(Condition.serializer(Sample.serializer()), always)))

            var last: WebSocketFrame? = null
            socket.onMessageSent = { last = it }

            val big = "x".repeat(50000)
            val changes = CollectionChanges((1..6).map { i -> EntryChange<Sample>(null, Sample(i.toString(), big)) })
            TestServer.ws.generalTopic.send(changes)

            val upd = json.decodeFromString(
                CollectionUpdates.serializer(Sample.serializer(), String.serializer()),
                (last as WebSocketFrame.Text).text
            )
            assertTrue(upd.overload == true, "six 50k-character rows must overload")
        }
    }

    @Test
    fun overload_triggers_when_payload_large() = runBlocking {
        TestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val socket = TestServer.ws.webSocket.test()
            val json = contextOf<ServerRuntime>().externalSerialization.json

            val always: Condition<Sample> = Condition.Always
            val frameText = json.encodeToString(Condition.serializer(Sample.serializer()), always)
            socket.send(WebSocketFrame.Text(frameText))

            var last: WebSocketFrame? = null
            socket.onMessageSent = { last = it }

            val changes = CollectionChanges((1..6000).map { i -> EntryChange<Sample>(null, Sample(i.toString(), "X")) })
            TestServer.ws.generalTopic.send(changes)

            val got = last as WebSocketFrame.Text
            val upd =
                json.decodeFromString(CollectionUpdates.serializer(Sample.serializer(), String.serializer()), got.text)
            assertTrue(upd.overload == true)
        }
    }
}

@Serializable
@GenerateDataClassPaths
data class Sample(override val _id: String, val name: String, val note: String = "") : HasId<String>