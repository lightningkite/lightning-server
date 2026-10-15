@file:OptIn(com.lightningkite.lightningserver.EngineApi::class)

package com.lightningkite.lightningserver.telemetry

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.telemetrySettings
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.Execution
import com.lightningkite.lightningserver.runtime.engine
import com.lightningkite.lightningserver.runtime.executeInlineWithMetrics
import com.lightningkite.lightningserver.runtime.handleRoot
import com.lightningkite.lightningserver.runtime.test.execute
import com.lightningkite.lightningserver.runtime.test.testBlocking
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryBackend
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.telemetry.TelemetryKeys
import kotlinx.serialization.builtins.serializer
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ErrorReportingTest {

    object Reports : TelemetryBackend by TelemetryBackend.Noop {
        val received: MutableList<Pair<Throwable, TelemetryAttributes>> = Collections.synchronizedList(mutableListOf())

        init {
            TelemetryBackend.Settings.register("recording") { _, _, _ -> Reports }
        }

        override fun reportError(throwable: Throwable, attributes: TelemetryAttributes) {
            received += throwable to attributes
        }
    }

    object TestServer : ServerBuilder() {
        init {
            registerBasicMediaTypeCoders()
        }

        val boom = path.path("boom").get bind HttpHandler<PathSpec0> { throw IllegalStateException("boom") }
        val missing = path.path("missing").get bind HttpHandler<PathSpec0> { throw NotFoundException() }
        val failingTask = path.path("failing") bind Task(Unit.serializer()) { throw IllegalStateException("task failed") }
    }

    private fun get(path: String) = HttpRequest(
        path = RawHttpEndpoint(asString = path, method = HttpMethod.GET),
        queryParameters = QueryParameters.EMPTY,
        headers = HttpHeaders.EMPTY,
        domain = "example.com",
        protocol = "https",
        sourceIp = "local",
    )

    @Test
    fun serverErrorsAreReportedAndClientErrorsAreNot() {
        Reports.received.clear()
        TestServer.testBlocking(
            settings = {
                Reports
                telemetrySettings.set(TelemetryBackend.Settings(url = "recording://"))
            }
        ) {
            assertEquals(500, engine.handleRoot(get("/boom"), Execution.ID.generate()).status.code)
            assertEquals(404, engine.handleRoot(get("/missing"), Execution.ID.generate()).status.code)
            assertFailsWith<IllegalStateException> { execute { failingTask.executeInlineWithMetrics(Unit) } }

            assertEquals(listOf("boom", "task failed"), Reports.received.map { it.first.message })
            assertEquals("/boom", Reports.received[0].second[TelemetryKeys.Http.route])
            assertEquals("/failing", Reports.received[1].second[TelemetryKey.OfString("task.route")])
        }
    }
}
