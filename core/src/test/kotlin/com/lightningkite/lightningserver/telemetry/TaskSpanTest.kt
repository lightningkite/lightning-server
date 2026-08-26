package com.lightningkite.lightningserver.telemetry

import com.lightningkite.lightningserver.definition.PreDeployTask
import com.lightningkite.lightningserver.definition.ScheduledTask
import com.lightningkite.lightningserver.definition.StartupTask
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.telemetrySettings
import com.lightningkite.lightningserver.runtime.executeWithMetrics
import com.lightningkite.lightningserver.runtime.location
import com.lightningkite.lightningserver.runtime.test.testBlocking
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.services.telemetry.TelemetryBackend
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Duration.Companion.hours

/**
 * Verifies that non-HTTP entry points (schedules, tasks, startup and pre-deploy tasks) name their
 * span after the thing that ran, not just its kind.
 *
 * Without the location in the name every schedule in a deployment collapses into a single
 * "lightningserver.schedule" trace name, which makes them indistinguishable in a trace browser.
 */
class TaskSpanTest {

    object TestServer : ServerBuilder() {
        val cleanup = path.path("cleanup") bind ScheduledTask(frequency = 1.hours) {}
        val reindex = path.path("reindex") bind ScheduledTask(frequency = 1.hours) {}
        val sendEmail = path.path("sendEmail") bind Task(Unit.serializer()) {}
        val migrate = path.path("migrate") bind StartupTask {}
        val warmup = path.path("warmup") bind PreDeployTask {}
    }

    private fun spanNames(): List<String> = InMemoryTelemetry.finishedSpans().map { it.name }

    private fun spanNamed(name: String) = InMemoryTelemetry.finishedSpans().singleOrNull { it.name == name }
        ?: fail("Expected a span named \"$name\". Got: ${spanNames()}")

    @Test
    fun entry_point_spans_are_named_by_location() {
        TestServer.testBlocking(
            settings = {
                InMemoryTelemetry  // ensure "memory" URL scheme is registered
                telemetrySettings.set(TelemetryBackend.Settings(url = "memory"))
            }
        ) {
            cleanup.executeWithMetrics(cleanup.location)
            reindex.executeWithMetrics(reindex.location)
            sendEmail.executeWithMetrics(sendEmail.location, Unit, cause = null)
            migrate.executeWithMetrics(migrate.location)
            warmup.executeWithMetrics(warmup.location)

            // Two schedules must produce two distinguishable trace names, not one shared one.
            val cleanupSpan = spanNamed("lightningserver.schedule /cleanup")
            spanNamed("lightningserver.schedule /reindex")
            spanNamed("lightningserver.task /sendEmail")
            spanNamed("lightningserver.startup /migrate")
            spanNamed("lightningserver.predeploy /warmup")

            // The route attribute stays alongside the name so existing dashboards keep working.
            assertEquals(
                "/cleanup",
                cleanupSpan.attributes.asMap().entries.first { it.key.key == "task.route" }.value,
            )
            assertEquals(
                "SCHEDULE",
                cleanupSpan.attributes.asMap().entries.first { it.key.key == "task.type" }.value,
            )
        }
    }
}
