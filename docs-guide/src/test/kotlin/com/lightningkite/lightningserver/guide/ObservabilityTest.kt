package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.definition.telemetrySettings
import com.lightningkite.lightningserver.guide.samples.ClinicServer
import com.lightningkite.lightningserver.guide.samples.PatientChart
import com.lightningkite.lightningserver.guide.samples.TracedShopServer
import com.lightningkite.lightningserver.runtime.test.execute
import com.lightningkite.lightningserver.runtime.test.executePreDeployTasks
import com.lightningkite.lightningserver.runtime.test.testBlocking
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.settings.setStatic
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.Namespaced
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Database
import com.lightningkite.services.telemetry.*
import kotlinx.coroutines.flow.toList
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObservabilityTest {
    private class RecordingBackend : TelemetryBackend by TelemetryBackend.Noop {
        val spans: MutableList<String> = Collections.synchronizedList(ArrayList())
        val counted: MutableList<String> = Collections.synchronizedList(ArrayList())

        override suspend fun <T> span(
            owner: Namespaced,
            opName: String,
            attributes: TelemetryAttributes,
            dimensions: Set<TelemetryKey<*>>,
            action: suspend (TelemetryTrace) -> T,
        ): T {
            spans += "${owner.name}.$opName"
            return TelemetryBackend.Noop.span(owner, opName, attributes, dimensions, action)
        }

        override fun counter(owner: Namespaced, name: String, unit: MetricUnit, dimensions: Set<TelemetryKey<*>>): Counter =
            object : Counter {
                override suspend fun increment(amount: Double) { counted += name }
            }
    }

    @Test
    fun `checkout produces request, handler and custom spans plus the counter`() {
        val backend = RecordingBackend()
        TracedShopServer.testBlocking(settings = {
            telemetrySettings setStatic backend
            cache set Cache.Settings()
        }) {
            assertEquals(1000, TracedShopServer.checkout.test(null, listOf("apple", "pear")))
        }
        assertTrue("lightningserver.price-cart" in backend.spans, backend.spans.toString())
        assertEquals(listOf("shop.orders.placed"), backend.counted)
    }

    @Test
    fun `audited changes are recorded`() = ClinicServer.testBlocking(settings = { database set Database.Settings() }) {
        executePreDeployTasks()
        val chart = ClinicServer.charts.insert.test(null, PatientChart(name = "Ada", diagnosis = "Flu"))
        execute {
            val mutations = ClinicServer.Audit.mutations.table().find(Condition.Always).toList()
            assertEquals(listOf(chart._id.asUuid), mutations.map { it.modelId })
            assertTrue(ClinicServer.Audit.queries.table().find(Condition.Always).toList().isNotEmpty())
        }
    }
}
