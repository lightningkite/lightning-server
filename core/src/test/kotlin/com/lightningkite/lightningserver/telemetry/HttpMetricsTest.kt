package com.lightningkite.lightningserver.telemetry

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals

class HttpMetricsTest {

    @Test
    fun `record increments counters correctly`() {
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

        assertEquals(0L, errorCountValue.get())
        assertEquals(45L, durationValue.get())

        // Test another request
        metrics.record(
            method = "POST",
            route = "/api/users",
            statusCode = 201,
            durationMs = 100
        )

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
