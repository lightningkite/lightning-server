package com.lightningkite.lightningserver.engine.local

import com.lightningkite.services.data.DataSize.Companion.mebibytes
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies that [EngineReliabilitySettings] serializes with sensible defaults and that an existing
 * `settings.json` lacking the new field still parses (every field is optional via its default).
 */
class EngineReliabilitySettingsTest {

    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    @Test
    fun defaults_match_spec() {
        val s = EngineReliabilitySettings()
        assertEquals(16.mebibytes, s.maxBodySize)
        assertEquals(120.seconds, s.idleTimeout)
        assertEquals(25.seconds, s.shutdownDrainTimeout)
        assertEquals(256, s.webSocketInboundBuffer)
        assertEquals(WsOversizePolicy.CLOSE, s.webSocketOversizePolicy)
        assertEquals(null, s.workerThreads)
    }

    @Test
    fun absent_fields_parse_to_defaults() {
        // An empty object (as would appear in an older settings file) must round-trip to all defaults.
        val parsed = json.decodeFromString(EngineReliabilitySettings.serializer(), "{}")
        assertEquals(EngineReliabilitySettings(), parsed)
    }

    @Test
    fun partial_object_keeps_other_defaults() {
        val parsed = json.decodeFromString(
            EngineReliabilitySettings.serializer(),
            """{"webSocketInboundBuffer":64,"webSocketOversizePolicy":"DROP_OLDEST"}""",
        )
        assertEquals(64, parsed.webSocketInboundBuffer)
        assertEquals(WsOversizePolicy.DROP_OLDEST, parsed.webSocketOversizePolicy)
        // Untouched fields retain defaults.
        assertEquals(16.mebibytes, parsed.maxBodySize)
    }

    @Test
    fun round_trips_through_json() {
        val original = EngineReliabilitySettings(
            webSocketInboundBuffer = 64,
            webSocketOversizePolicy = WsOversizePolicy.SUSPEND,
            workerThreads = 8,
        )
        val text = json.encodeToString(EngineReliabilitySettings.serializer(), original)
        val back = json.decodeFromString(EngineReliabilitySettings.serializer(), text)
        assertEquals(original, back)
    }
}
