package com.lightningkite.lightningserver.typed.contract

import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.LightningServerKSchema
import com.lightningkite.lightningserver.typed.kschema.lightningServerKSchemaFromDefaultRuntime
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * End-to-end tests that capture real raw [LightningServerKSchema]s from [ServerBuilder]s (offline) and diff mutated
 * servers against a baseline, asserting the expected taxonomy codes are produced.
 */
@Serializable
@GenerateDataClassPaths
data class Widget(
    override val _id: Uuid = Uuid.random(),
    val name: String,
    val color: String,
) : HasId<Uuid>

@Serializable
@GenerateDataClassPaths
data class WidgetV2(
    override val _id: Uuid = Uuid.random(),
    val name: String,
) : HasId<Uuid>

class ApiContractGenerationTest {

    object BaselineServer : ServerBuilder() {
        init {
            registerBasicMediaTypeCoders()
        }
        val database = setting("database", Database.Settings())
        val cache = setting("cache", Cache.Settings())
        val widgets = path.path("widgets").post bind ApiHttpHandler(
            summary = "List Widgets",
            auth = noAuth,
            implementation = { _: Unit -> Widget(name = "x", color = "red") },
        )
        val ping = path.path("ping").post bind ApiHttpHandler(
            summary = "Ping",
            auth = noAuth,
            implementation = { _: Unit -> "pong" },
        )
    }

    // Removes the /ping endpoint and removes the "color" output field of Widget (WidgetV2 drops it).
    object MutatedServer : ServerBuilder() {
        init {
            registerBasicMediaTypeCoders()
        }
        val database = setting("database", Database.Settings())
        val cache = setting("cache", Cache.Settings())
        val widgets = path.path("widgets").post bind ApiHttpHandler(
            summary = "List Widgets",
            auth = noAuth,
            implementation = { _: Unit -> WidgetV2(name = "x") },
        )
    }

    @Test
    fun captureIsDeterministic() {
        val a = BaselineServer.lightningServerKSchemaFromDefaultRuntime.canonicalize()
        val b = BaselineServer.lightningServerKSchemaFromDefaultRuntime.canonicalize()
        assertEquals(
            apiBaselineJson.encodeToString(LightningServerKSchema.serializer(), a),
            apiBaselineJson.encodeToString(LightningServerKSchema.serializer(), b),
            "Capturing the same server twice must produce identical schemas",
        )
    }

    @Test
    fun mutationProducesBreakingChanges() {
        val baseline = BaselineServer.lightningServerKSchemaFromDefaultRuntime.canonicalize()
        val current = MutatedServer.lightningServerKSchemaFromDefaultRuntime.canonicalize()
        val report = diffApiContract(baseline, current)
        val codes = report.changes.map { it.code }.toSet()
        assertTrue(ApiChangeCode.ENDPOINT_REMOVED in codes, "Removing /ping should be ENDPOINT_REMOVED; got $codes")
        // The widget output type changed from Widget to WidgetV2 (different serial name) -> TYPE_CHANGED on output.
        assertTrue(
            ApiChangeCode.TYPE_CHANGED in codes || ApiChangeCode.OUTPUT_FIELD_REMOVED in codes,
            "Dropping the color field should be breaking; got $codes",
        )
        assertTrue(report.hasFailures(), "Report should fail the compatibility gate")
    }

    @Test
    fun identicalServerHasNoBreakingChanges() {
        val baseline = BaselineServer.lightningServerKSchemaFromDefaultRuntime.canonicalize()
        val current = BaselineServer.lightningServerKSchemaFromDefaultRuntime.canonicalize()
        val report = diffApiContract(baseline, current)
        assertEquals(0, report.changes.size, "Identical server should produce no changes")
    }
}
