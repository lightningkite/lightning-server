package com.lightningkite.lightningserver.metrics

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.HtmlDefaults
import com.lightningkite.lightningserver.auth.rawUser
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.exceptions.ForbiddenException
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.http.Http
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.handler
import com.lightningkite.lightningserver.meta.MetaEndpoints
import com.lightningkite.lightningserver.schedule.Scheduler
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.queryParameters
import com.lightningkite.lightningserver.serialization.toHttpContent
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.websocket.WebSockets
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

class DatabaseMetrics(override val settings: MetricSettings, val database: () -> Database) :
    ServerPathGroup(ServerPath.root.path("meta/metrics")), Metrics {
    init {
        prepareModels()
    }

    val keepFor: Map<Duration, Duration> = mapOf(
        Duration.ofDays(1) to Duration.ofDays(7),
        Duration.ofHours(2) to Duration.ofDays(1),
        Duration.ofMinutes(10) to Duration.ofHours(2),
    )

    val collection by lazy { database().collection<MetricSpanStats>() }

    override suspend fun report(events: List<MetricEvent>) = coroutineScope {
        val jobs = ArrayList<Job>()
        for (span in keepFor.keys) {
            events.filter { it.entryPoint != null }.groupBy { it.metricType to it.entryPoint }.forEach { (typeAndEntryPoint, typeEvents) ->
                val (type, entryPoint) = typeAndEntryPoint
                if (type.name in settings.trackingByEntryPoint) {
                    typeEvents.groupBy { it.time.roundTo(span) }.forEach { (rounded, spanEvents) ->
                        val stats = spanEvents.stats(entryPoint!!, type.name, rounded, span)
                        jobs.add(launch {
                            collection.upsertOneIgnoringResult(
                                condition { m -> m._id eq stats._id },
                                stats.asModification(),
                                stats
                            )
                        })
                    }
                }
            }
            events.groupBy { it.metricType }.forEach { (type, typeEvents) ->
                if (type.name in settings.trackingTotalsOnly || type.name in settings.trackingByEntryPoint) {
                    typeEvents.groupBy { it.time.roundTo(span) }.forEach { (rounded, spanEvents) ->
                        val stats = spanEvents.stats("total", type.name, rounded, span)
                        jobs.add(launch {
                            collection.upsertOneIgnoringResult(
                                condition { m -> m._id eq stats._id },
                                stats.asModification(),
                                stats
                            )
                        })
                    }
                }
            }
        }
        Metrics.logger.debug("Sending reports...")
        jobs.forEach { it.join() }
        Metrics.logger.debug("Reports sent.")
        Unit
    }

    override suspend fun clean() {
        keepFor.entries.forEach { entry ->
            collection.deleteManyIgnoringOld(condition {
                (it.timeSpan eq entry.key) and
                        (it.timeStamp lt Instant.now().minus(entry.value))
            })
        }
    }

    val dashboard = get.handler { req ->
        if (!MetaEndpoints.isAdministrator(req.rawUser())) throw ForbiddenException()
        HttpResponse.html(
            content = HtmlDefaults.basePage(
                buildString {
                    for (span in keepFor.keys) {
                        appendLine("<h2>$span</h2>")
                        appendLine("<h3>Most Expensive</h3>")
                        collection.find(
                            condition = condition { it.timeSpan.eq(span) and it.endpoint.neq("total") and it.type.eq("executionTime") },
                            orderBy = listOf(SortPart(MetricSpanStats::sum, ascending = false)),
                            limit = 10
                        ).toList().forEach {
                            appendLine("<p>${it.endpoint} - ${it.sum} ms</p>")
                        }
                    }

                    appendLine("<div><a href=${visualizeIndexA.path}>Graph List</a></div>")
                }
            )
        )
    }
    val reportEndpoint = get("raw").handler { req ->
        if (!MetaEndpoints.isAdministrator(req.rawUser())) throw ForbiddenException()
        val result = collection.query(req.queryParameters()).toList()
        HttpResponse(
            body = result.toHttpContent(req.headers.accept)
        )
    }
    val visualizeIndexA = get("visual").handler {
        if (!MetaEndpoints.isAdministrator(it.rawUser())) throw ForbiddenException()
        HttpResponse.html(content = HtmlDefaults.basePage(buildString {
            appendLine("<ul>")
            for (metric in settings.trackingByEntryPoint) {
                appendLine(
                    "<li><a href=${
                        visualizeIndexB.path.toString(
                            mapOf(
                                "metric" to metric
                            )
                        )
                    }>$metric</li>"
                )
            }
            appendLine("</ul>")
        }))
    }
    val visualizeIndexB = get("visual/{metric}").handler {
        if (!MetaEndpoints.isAdministrator(it.rawUser())) throw ForbiddenException()
        HttpResponse.html(content = HtmlDefaults.basePage(buildString {
            appendLine("<ul>")
            val endpoints =
                listOf("total") + Http.endpoints.keys.map { it.toString() } + WebSockets.handlers.keys.flatMap {
                    listOf(
                        WebSockets.HandlerSection(it, WebSockets.WsHandlerType.CONNECT),
                        WebSockets.HandlerSection(it, WebSockets.WsHandlerType.MESSAGE),
                        WebSockets.HandlerSection(it, WebSockets.WsHandlerType.DISCONNECT),
                    ).map { it.toString() }
                } + Scheduler.schedules.map { "SCHEDULE " + it.key } + Tasks.tasks.keys.map { "TASK $it" }
            for (endpoint in endpoints) {
                appendLine(
                    "<li><a href=${
                        visualizeIndexC.path.toString(
                            mapOf(
                                "metric" to it.parts["metric"]!!,
                                "endpoint" to endpoint,
                            )
                        )
                    }>$endpoint</a></li>"
                )

            }
            appendLine("</ul>")
        }))
    }
    val visualizeIndexC = get("visual/{metric}/{endpoint}").handler {
        if (!MetaEndpoints.isAdministrator(it.rawUser())) throw ForbiddenException()
        HttpResponse.html(content = HtmlDefaults.basePage(buildString {
            appendLine("<ul>")
            val metric = it.parts["metric"]!!
            val endpoint = it.parts["endpoint"]!!
            for (span in keepFor.keys) {
                for (summary in listOf("min", "max", "sum", "count", "average")) {
                    appendLine(
                        "<li><a href=${
                            visualizeSpecific.path.toString(
                                mapOf(
                                    "metric" to metric,
                                    "endpoint" to endpoint,
                                    "span" to span.toString(),
                                    "summary" to summary,
                                )
                            )
                        }>$metric $endpoint $span $summary</a></li>"
                    )
                }
            }
            appendLine("</ul>")
        }))
    }
    val visualizeSpecific = get("visual/{metric}/{endpoint}/{span}/{summary}").handler {
        if (!MetaEndpoints.isAdministrator(it.rawUser())) throw ForbiddenException()
        val metric = it.parts.getValue("metric")
        val endpoint = it.parts.getValue("endpoint")
        val span = Serialization.fromString(it.parts.getValue("span"), DurationSerializer)
        val summaryName = it.parts.getValue("summary")
        val summary: (MetricSpanStats) -> Double = when (summaryName) {
            "min" -> {
                { it.min }
            }

            "max" -> {
                { it.max }
            }

            "sum" -> {
                { it.sum }
            }

            "count" -> {
                { it.count.toDouble() }
            }

            "average" -> {
                { it.sum / it.count.toDouble() }
            }

            else -> throw NotFoundException("No metric summary '$summaryName'")
        }

        val entries = collection.query(
            Query(
                condition = condition {
                    (it.type eq metric) and (it.timeSpan eq span) and (it.endpoint eq endpoint)
                },
                orderBy = listOf(SortPart(MetricSpanStats::timeStamp)),
                limit = 1000
            )
        ).toList()
        if (entries.isEmpty()) throw NotFoundException("No data found, looking for $endpoint|$metric|x|$span")
        //language=HTML
        HttpResponse.html(
            content = HtmlDefaults.basePage(
                """
                    <canvas id='chart' width='640' height='480'></canvas>
                    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
                    <script>
                       new Chart(document.getElementById('chart'), {
                        type: 'bar',
                        data: {
                          labels: [${entries.joinToString { "\"${it.timeStamp}\"" }}],
                          datasets: [{
                            label: '$metric',
                            data: [${entries.joinToString { summary(it).toString() }}],
                            borderWidth: 1
                          }]
                        },
                        options: {
                          scales: {
                            y: {
                              beginAtZero: true
                            }
                          },
                          maintainAspectRatio: false
                        }
                       })
                    </script>
        """.trimIndent()
            )
        )
    }
}
