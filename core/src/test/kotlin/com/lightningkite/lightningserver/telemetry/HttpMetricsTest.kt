package com.lightningkite.lightningserver.telemetry

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals

class HttpMetricsTest {

    @Test
    fun `statusCategory returns correct category for various status codes`() {
        // 1xx Informational
        assertEquals("1xx", HttpMetrics.statusCategory(100))
        assertEquals("1xx", HttpMetrics.statusCategory(101))
        assertEquals("1xx", HttpMetrics.statusCategory(199))

        // 2xx Success
        assertEquals("2xx", HttpMetrics.statusCategory(200))
        assertEquals("2xx", HttpMetrics.statusCategory(201))
        assertEquals("2xx", HttpMetrics.statusCategory(204))
        assertEquals("2xx", HttpMetrics.statusCategory(299))

        // 3xx Redirection
        assertEquals("3xx", HttpMetrics.statusCategory(300))
        assertEquals("3xx", HttpMetrics.statusCategory(301))
        assertEquals("3xx", HttpMetrics.statusCategory(307))
        assertEquals("3xx", HttpMetrics.statusCategory(399))

        // 4xx Client Error
        assertEquals("4xx", HttpMetrics.statusCategory(400))
        assertEquals("4xx", HttpMetrics.statusCategory(401))
        assertEquals("4xx", HttpMetrics.statusCategory(404))
        assertEquals("4xx", HttpMetrics.statusCategory(499))

        // 5xx Server Error
        assertEquals("5xx", HttpMetrics.statusCategory(500))
        assertEquals("5xx", HttpMetrics.statusCategory(502))
        assertEquals("5xx", HttpMetrics.statusCategory(503))
        assertEquals("5xx", HttpMetrics.statusCategory(599))

        // Unknown
        assertEquals("unknown", HttpMetrics.statusCategory(0))
        assertEquals("unknown", HttpMetrics.statusCategory(99))
        assertEquals("unknown", HttpMetrics.statusCategory(600))
        assertEquals("unknown", HttpMetrics.statusCategory(-1))
    }

    @Test
    fun `record increments counters correctly`() {
        val requestCountValue = AtomicLong(0)
        val responseCategoryValue = AtomicLong(0)
        val errorCountValue = AtomicLong(0)
        val durationValue = AtomicLong(0)

        val mockMeter = object : Meter {
            override fun counterBuilder(name: String): LongCounterBuilder {
                return object : LongCounterBuilder {
                    override fun setDescription(description: String) = this
                    override fun setUnit(unit: String) = this
                    override fun ofDoubles() = throw UnsupportedOperationException()
                    override fun buildObserver() = throw UnsupportedOperationException()
                    override fun buildWithCallback(callback: java.util.function.Consumer<ObservableLongMeasurement>) =
                        throw UnsupportedOperationException()

                    override fun build(): LongCounter {
                        return object : LongCounter {
                            override fun add(value: Long) {
                                when (name) {
                                    "http.server.request.count" -> requestCountValue.addAndGet(value)
                                    "http.server.response.status.category" -> responseCategoryValue.addAndGet(value)
                                    "http.server.errors" -> errorCountValue.addAndGet(value)
                                }
                            }

                            override fun add(value: Long, attributes: Attributes) = add(value)
                            override fun add(
                                value: Long,
                                attributes: Attributes,
                                context: io.opentelemetry.context.Context,
                            ) = add(value)
                        }
                    }
                }
            }

            override fun histogramBuilder(name: String): io.opentelemetry.api.metrics.DoubleHistogramBuilder {
                return object : io.opentelemetry.api.metrics.DoubleHistogramBuilder {
                    override fun setDescription(description: String) = this
                    override fun setUnit(unit: String) = this
                    override fun setExplicitBucketBoundariesAdvice(buckets: MutableList<Double>) = this
                    override fun ofLongs(): LongHistogramBuilder {
                        return object : LongHistogramBuilder {
                            override fun setDescription(description: String) = this
                            override fun setUnit(unit: String) = this
                            override fun setExplicitBucketBoundariesAdvice(buckets: MutableList<Long>) = this
                            override fun build(): LongHistogram {
                                return object : LongHistogram {
                                    override fun record(value: Long) {
                                        durationValue.set(value)
                                    }

                                    override fun record(value: Long, attributes: Attributes) = record(value)
                                    override fun record(
                                        value: Long,
                                        attributes: Attributes,
                                        context: io.opentelemetry.context.Context,
                                    ) = record(value)
                                }
                            }
                        }
                    }

                    override fun build() = throw UnsupportedOperationException()
                }
            }

            override fun gaugeBuilder(name: String) = throw UnsupportedOperationException()
            override fun upDownCounterBuilder(name: String) = throw UnsupportedOperationException()
            override fun batchCallback(
                callback: Runnable,
                observableMeasurement: ObservableMeasurement,
                vararg additionalMeasurements: ObservableMeasurement?,
            ): BatchCallback = throw UnsupportedOperationException()
        }

        val metrics = HttpMetrics(mockMeter)

        // Test successful request
        metrics.record(
            method = "GET",
            route = "/api/users",
            statusCode = 200,
            durationMs = 45
        )

        assertEquals(1L, requestCountValue.get())
        assertEquals(1L, responseCategoryValue.get())
        assertEquals(0L, errorCountValue.get())
        assertEquals(45L, durationValue.get())

        // Test another request
        metrics.record(
            method = "POST",
            route = "/api/users",
            statusCode = 201,
            durationMs = 100
        )

        assertEquals(2L, requestCountValue.get())
        assertEquals(2L, responseCategoryValue.get())
        assertEquals(0L, errorCountValue.get())
        assertEquals(100L, durationValue.get())

        // Test error request
        metrics.record(
            method = "GET",
            route = "/api/users/123",
            statusCode = 500,
            durationMs = 200,
            errorType = "NullPointerException"
        )

        assertEquals(3L, requestCountValue.get())
        assertEquals(3L, responseCategoryValue.get())
        assertEquals(1L, errorCountValue.get())
        assertEquals(200L, durationValue.get())
    }

    @Test
    fun `record does not increment errors for 4xx status codes`() {
        val errorCountValue = AtomicLong(0)

        val mockMeter = createMockMeterTracking { name ->
            if (name == "http.server.errors") errorCountValue
            else AtomicLong(0)
        }

        val metrics = HttpMetrics(mockMeter)

        metrics.record(
            method = "GET",
            route = "/api/users",
            statusCode = 404,
            durationMs = 10
        )

        assertEquals(0L, errorCountValue.get(), "4xx errors should not increment error counter")

        metrics.record(
            method = "POST",
            route = "/api/users",
            statusCode = 400,
            durationMs = 10
        )

        assertEquals(0L, errorCountValue.get(), "400 should not increment error counter")
    }

    private fun createMockMeterTracking(getCounter: (String) -> AtomicLong): Meter {
        return object : Meter {
            override fun counterBuilder(name: String): LongCounterBuilder {
                return object : LongCounterBuilder {
                    override fun setDescription(description: String) = this
                    override fun setUnit(unit: String) = this
                    override fun ofDoubles() = throw UnsupportedOperationException()
                    override fun buildObserver() = throw UnsupportedOperationException()
                    override fun buildWithCallback(callback: java.util.function.Consumer<ObservableLongMeasurement>) =
                        throw UnsupportedOperationException()

                    override fun build(): LongCounter {
                        val counter = getCounter(name)
                        return object : LongCounter {
                            override fun add(value: Long) {
                                counter.addAndGet(value)
                            }

                            override fun add(value: Long, attributes: Attributes) = add(value)
                            override fun add(
                                value: Long,
                                attributes: Attributes,
                                context: io.opentelemetry.context.Context,
                            ) = add(value)
                        }
                    }
                }
            }

            override fun histogramBuilder(name: String): io.opentelemetry.api.metrics.DoubleHistogramBuilder {
                return object : io.opentelemetry.api.metrics.DoubleHistogramBuilder {
                    override fun setDescription(description: String) = this
                    override fun setUnit(unit: String) = this
                    override fun setExplicitBucketBoundariesAdvice(buckets: MutableList<Double>) = this
                    override fun ofLongs(): LongHistogramBuilder {
                        return object : LongHistogramBuilder {
                            override fun setDescription(description: String) = this
                            override fun setUnit(unit: String) = this
                            override fun setExplicitBucketBoundariesAdvice(buckets: MutableList<Long>) = this
                            override fun build(): LongHistogram {
                                return object : LongHistogram {
                                    override fun record(value: Long) {}
                                    override fun record(value: Long, attributes: Attributes) {}
                                    override fun record(
                                        value: Long,
                                        attributes: Attributes,
                                        context: io.opentelemetry.context.Context,
                                    ) {
                                    }
                                }
                            }
                        }
                    }

                    override fun build() = throw UnsupportedOperationException()
                }
            }

            override fun gaugeBuilder(name: String) = throw UnsupportedOperationException()
            override fun upDownCounterBuilder(name: String) = throw UnsupportedOperationException()
            override fun batchCallback(
                callback: Runnable,
                observableMeasurement: ObservableMeasurement,
                vararg additionalMeasurements: ObservableMeasurement?,
            ): BatchCallback = throw UnsupportedOperationException()
        }
    }
}
