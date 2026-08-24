package com.lightningkite.lightningserver.typed

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
import com.lightningkite.services.database.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * `ModelRestUpdatesWebSocket` used to resolve the read mask once, at connect, and reuse it for the
 * whole life of the socket. A long-lived connection therefore kept disclosing under permissions that
 * may have been revoked minutes or hours earlier, and a revocation only took effect on reconnect.
 *
 * These tests pin the fix: permissions are a cache with a deadline, re-derived on the first push
 * after [ModelRestUpdatesWebSocket.permissionRevalidation] elapses.
 */
class WebSocketPermissionStalenessTest {

    /** Swapped mid-test to simulate a permission change while a socket is open. */
    private object Permissions {
        val unrestricted: ModelPermissions<Sample> = ModelPermissions.allowAll()

        /** Same read access, but `note` is redacted — a narrowing that must reach an open socket. */
        val noteRedacted: ModelPermissions<Sample> = ModelPermissions(
            create = Condition.Always,
            read = Condition.Always,
            update = Condition.Always,
            delete = Condition.Always,
            readMask = mask<Sample> { it.note.mask("") },
        )

        @Volatile
        var current: ModelPermissions<Sample> = unrestricted
    }

    private val revalidateAfter = 5.minutes

    private inner class Fixture : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val info = database.modelInfo<HasId<*>?, Sample, String>(
            tableName = "Sample",
            auth = noAuth,
            permissions = { Permissions.current }
        )
        val ws = path.path("stale").path("updates") include
                ModelRestUpdatesWebSocket(info, permissionRevalidation = revalidateAfter)

        init {
            registerBasicMediaTypeCoders()
        }
    }

    /** A clock the test advances by hand, so revalidation is exercised without waiting. */
    private class MovableClock(var instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    /**
     * Opens a socket watching every [Sample], and hands the body a `push` that sends one change and
     * returns the `note` the client actually received — the field the narrowed permissions redact.
     */
    private fun scenario(block: (clock: MovableClock, push: (Sample) -> String?) -> Unit) {
        Permissions.current = Permissions.unrestricted
        val fixture = Fixture()
        val clock = MovableClock(Instant.fromEpochSeconds(1_700_000_000))
        fixture.test(
            settings = {
                generalSettings set GeneralServerSettings()
                database set Database.Settings()
            },
            clock = { clock },
        ) {
            runBlocking {
                val socket = fixture.ws.webSocket.test()
                val json = socket.server.externalSerialization.json
                val watchEverything = WebSocketFrame.Text(
                    json.encodeToString(Condition.serializer(Sample.serializer()), Condition.Always)
                )
                socket.send(watchEverything)

                var last: WebSocketFrame? = null
                socket.onMessageSent = { last = it }

                block(clock) { sample ->
                    last = null
                    runBlocking {
                        fixture.ws.generalTopic.send(
                            CollectionChanges(listOf(EntryChange(old = null, new = sample)))
                        )
                    }
                    (last as? WebSocketFrame.Text)?.let { frame ->
                        json.decodeFromString(
                            CollectionUpdates.serializer(Sample.serializer(), String.serializer()),
                            frame.text,
                        ).updates.singleOrNull()?.note
                    }
                }
            }
        }
    }

    @Test
    fun `a narrowed mask takes effect once the revalidation window elapses`() = scenario { clock, push ->
        assertEquals("secret", push(Sample("1", "A", "secret")))

        Permissions.current = Permissions.noteRedacted
        clock.instant += revalidateAfter + 1.seconds

        assertEquals(
            "",
            push(Sample("2", "B", "secret")),
            "the socket kept disclosing under permissions that were revoked before this push",
        )
    }

    @Test
    fun `permissions are not re-derived on every push`() = scenario { clock, push ->
        assertEquals("secret", push(Sample("1", "A", "secret")))

        // Narrow permissions but stay inside the window: the cached mask is expected to still apply.
        // This is the cost half of the tradeoff — re-resolving per push would hit the database for
        // every subscriber on every change.
        Permissions.current = Permissions.noteRedacted
        clock.instant += revalidateAfter / 2

        assertEquals(
            "secret",
            push(Sample("2", "B", "secret")),
            "permissions were re-derived inside the revalidation window",
        )
    }
}
