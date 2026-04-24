// by Claude
package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.definition.GeneralServerSettings
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.services.data.HealthStatus
import com.lightningkite.services.data.ZonedDateTime
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.*
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes

/**
 * Tests for FunnelEndpoints - user funnel tracking and analytics.
 */
class FunnelEndpointsTest {

    object TestServer : ServerBuilder() {
        val database = setting("database", Database.Settings())

        // Use AuthRequirement.IsAdmin which has the correct type for FunnelEndpoints
        val funnel = path.path("funnel") include FunnelEndpoints(database, read = AuthRequirement.IsAdmin)
    }

    @Test
    fun start_creates_funnel_instance() = runBlocking {
        TestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val funnelStart = FunnelStart(
                funnel = "test-funnel",
                userAgent = "TestAgent/1.0",
                version = "1.0.0",
                expireAfterMinutes = 30
            )

            val id = TestServer.funnel.start.test(null, funnelStart)

            assertNotNull(id)

            // Verify the instance was created
            val instance = TestServer.funnel.info.table().get(id)
            assertNotNull(instance)
            assertEquals("test-funnel", instance.funnel)
            assertEquals("TestAgent/1.0", instance.userAgent)
            assertEquals("1.0.0", instance.version)
        }
    }

    @Test
    fun error_adds_error_to_funnel_instance() = runBlocking {
        TestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            // First start a funnel
            val funnelStart = FunnelStart(
                funnel = "error-test-funnel",
                userAgent = "TestAgent/1.0",
                version = "1.0.0"
            )
            val id = TestServer.funnel.start.test(null, funnelStart)

            // Record an error
            TestServer.funnel.error.test(id, null, "Test error message")

            // Verify the error was added
            val instance = TestServer.funnel.info.table().get(id)
            assertNotNull(instance)
            assertTrue(instance.errors.contains("Test error message"))
        }
    }

    @Test
    fun error_adds_multiple_errors() = runBlocking {
        TestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val funnelStart = FunnelStart(
                funnel = "multi-error-funnel",
                userAgent = "TestAgent/1.0",
                version = "1.0.0"
            )
            val id = TestServer.funnel.start.test(null, funnelStart)

            // Record multiple errors
            TestServer.funnel.error.test(id, null, "Error 1")
            TestServer.funnel.error.test(id, null, "Error 2")
            TestServer.funnel.error.test(id, null, "Error 3")

            // Verify all errors were added
            val instance = TestServer.funnel.info.table().get(id)
            assertNotNull(instance)
            assertEquals(3, instance.errors.size)
            assertTrue(instance.errors.containsAll(setOf("Error 1", "Error 2", "Error 3")))
        }
    }

    @Test
    fun step_updates_funnel_step() = runBlocking {
        TestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val funnelStart = FunnelStart(
                funnel = "step-test-funnel",
                userAgent = "TestAgent/1.0",
                version = "1.0.0"
            )
            val id = TestServer.funnel.start.test(null, funnelStart)

            // Update step
            TestServer.funnel.step.test(id, null, 3)

            // Verify the step was updated
            val instance = TestServer.funnel.info.table().get(id)
            assertNotNull(instance)
            assertEquals(3, instance.step)
        }
    }

    @Test
    fun success_marks_funnel_as_successful() = runBlocking {
        TestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val funnelStart = FunnelStart(
                funnel = "success-test-funnel",
                userAgent = "TestAgent/1.0",
                version = "1.0.0"
            )
            val id = TestServer.funnel.start.test(null, funnelStart)

            // Initially, success should be null
            val initialInstance = TestServer.funnel.info.table().get(id)
            assertNotNull(initialInstance)
            assertEquals(null, initialInstance.success)

            // Mark as successful
            TestServer.funnel.success.test(id, null, Unit)

            // Verify success was recorded
            val instance = TestServer.funnel.info.table().get(id)
            assertNotNull(instance)
            assertNotNull(instance.success)
        }
    }

    @Test
    fun summarize_creates_summary_from_instances() = runBlocking {
        TestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            // Clear any existing data
            TestServer.funnel.info.table().deleteManyIgnoringOld(Condition.Always)
            TestServer.funnel.summaryInfo.table().deleteManyIgnoringOld(Condition.Always)

            val targetDate = LocalDate(2024, 1, 15)
            val zone = TimeZone.of("America/Denver")
            val dayStart = ZonedDateTime(LocalDateTime(targetDate, LocalTime(8, 0, 0)), zone).toInstant()

            // Insert test funnel instances directly
            val testFunnel = "summarize-test-funnel"

            // 2 successful (no errors)
            // Note: summarize uses gt/lt (strict comparison), so we need to start > dayStart
            repeat(2) {
                TestServer.funnel.info.table().insertOne(
                    FunnelInstance(
                        funnel = testFunnel,
                        userAgent = "Test",
                        version = "1.0",
                        started = dayStart + (it + 1).minutes,  // Start at 1 minute after dayStart
                        expiry = dayStart + 20.minutes,
                        success = dayStart + 5.minutes,
                        errors = emptySet()
                    )
                )
            }

            // 1 successful after error
            TestServer.funnel.info.table().insertOne(
                FunnelInstance(
                    funnel = testFunnel,
                    userAgent = "Test",
                    version = "1.0",
                    started = dayStart + 10.minutes,
                    expiry = dayStart + 30.minutes,
                    success = dayStart + 15.minutes,
                    errors = setOf("recoverable error")
                )
            )

            // 1 error (no success)
            TestServer.funnel.info.table().insertOne(
                FunnelInstance(
                    funnel = testFunnel,
                    userAgent = "Test",
                    version = "1.0",
                    started = dayStart + 20.minutes,
                    expiry = dayStart + 40.minutes,
                    success = null,
                    errors = setOf("fatal error")
                )
            )

            // 1 abandoned (no success, no errors)
            TestServer.funnel.info.table().insertOne(
                FunnelInstance(
                    funnel = testFunnel,
                    userAgent = "Test",
                    version = "1.0",
                    started = dayStart + 30.minutes,
                    expiry = dayStart + 50.minutes,
                    success = null,
                    errors = emptySet()
                )
            )

            // Run summarize for that date
            TestServer.funnel.summarizeNow.test(null, targetDate)

            // Verify summary was created
            val summaries =
                TestServer.funnel.summaryInfo.table().find(condition<FunnelSummary> { it.date.eq(targetDate) }).toList()
            assertEquals(1, summaries.size)

            val summary = summaries.first()
            assertEquals(testFunnel, summary.funnel)
            assertEquals(targetDate, summary.date)
            assertEquals(5, summary.count)

            // 2 out of 5 = 0.4 success rate
            assertEquals(0.4f, summary.success, 0.01f)
            // 1 out of 5 = 0.2 success after error
            assertEquals(0.2f, summary.successAfterError, 0.01f)
            // 1 out of 5 = 0.2 error rate
            assertEquals(0.2f, summary.error, 0.01f)
            // 1 out of 5 = 0.2 abandoned rate
            assertEquals(0.2f, summary.abandoned, 0.01f)
        }
    }

    @Test
    fun summaries_returns_summaries_for_date() = runBlocking {
        TestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            // Clear any existing data
            TestServer.funnel.summaryInfo.table().deleteManyIgnoringOld(Condition.Always)

            val targetDate = LocalDate(2024, 2, 20)

            // Insert test summaries directly
            val summary1 = FunnelSummary(
                funnel = "funnel-a",
                date = targetDate,
                status = HealthStatus.Level.OK,
                success = 0.8f,
                successAfterError = 0.1f,
                error = 0.05f,
                abandoned = 0.05f,
                count = 100
            )
            val summary2 = FunnelSummary(
                funnel = "funnel-b",
                date = targetDate,
                status = HealthStatus.Level.WARNING,
                success = 0.6f,
                successAfterError = 0.2f,
                error = 0.1f,
                abandoned = 0.1f,
                count = 50
            )
            // Add a summary for a different date (should not be returned)
            val summaryOtherDate = FunnelSummary(
                funnel = "funnel-c",
                date = LocalDate(2024, 2, 21),
                status = HealthStatus.Level.OK,
                success = 0.9f,
                count = 30
            )

            TestServer.funnel.summaryInfo.table().insertOne(summary1)
            TestServer.funnel.summaryInfo.table().insertOne(summary2)
            TestServer.funnel.summaryInfo.table().insertOne(summaryOtherDate)

            // Get summaries for target date
            val result = TestServer.funnel.summaries.test(targetDate, null, Unit)

            assertEquals(2, result.size)
            assertTrue(result.any { it.funnel == "funnel-a" })
            assertTrue(result.any { it.funnel == "funnel-b" })
            assertTrue(result.none { it.funnel == "funnel-c" })
        }
    }

    @Test
    fun summarize_calculates_health_status_ok() = runBlocking {
        TestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            TestServer.funnel.info.table().deleteManyIgnoringOld(Condition.Always)
            TestServer.funnel.summaryInfo.table().deleteManyIgnoringOld(Condition.Always)

            val targetDate = LocalDate(2024, 3, 1)
            val zone = TimeZone.of("America/Denver")
            val dayStart = ZonedDateTime(LocalDateTime(targetDate, LocalTime(8, 0, 0)), zone).toInstant()

            val testFunnel = "health-ok-funnel"
            val expectedErrorRate = 0.2f  // 20% expected

            // Insert 100 successful instances, 1 error (1% actual error rate, well below 10% threshold)
            // Note: summarize uses gt/lt (strict comparison), so we need to start > dayStart
            repeat(100) {
                TestServer.funnel.info.table().insertOne(
                    FunnelInstance(
                        funnel = testFunnel,
                        userAgent = "Test",
                        version = "1.0",
                        started = dayStart + (it + 1).minutes,  // Start at 1 minute after dayStart
                        expiry = dayStart + (it + 21).minutes,
                        success = dayStart + (it + 6).minutes,
                        errors = emptySet(),
                        expectedErrorRate = expectedErrorRate
                    )
                )
            }
            TestServer.funnel.info.table().insertOne(
                FunnelInstance(
                    funnel = testFunnel,
                    userAgent = "Test",
                    version = "1.0",
                    started = dayStart + 102.minutes,
                    expiry = dayStart + 122.minutes,
                    success = null,
                    errors = setOf("error"),
                    expectedErrorRate = expectedErrorRate
                )
            )

            TestServer.funnel.summarizeNow.test(null, targetDate)

            val summaries =
                TestServer.funnel.summaryInfo.table().find(condition<FunnelSummary> { it.date.eq(targetDate) }).toList()
            assertEquals(1, summaries.size)

            val summary = summaries.first()
            // Error rate ~1% is less than half of 20% (10%), so should be OK
            assertEquals(HealthStatus.Level.OK, summary.status)
        }
    }

    @Test
    fun summarize_calculates_health_status_warning() = runBlocking {
        TestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            TestServer.funnel.info.table().deleteManyIgnoringOld(Condition.Always)
            TestServer.funnel.summaryInfo.table().deleteManyIgnoringOld(Condition.Always)

            val targetDate = LocalDate(2024, 3, 2)
            val zone = TimeZone.of("America/Denver")
            val dayStart = ZonedDateTime(LocalDateTime(targetDate, LocalTime(8, 0, 0)), zone).toInstant()

            val testFunnel = "health-warning-funnel"
            val expectedErrorRate = 0.2f  // 20% expected

            // Insert 85 successful instances, 15 errors (15% actual error rate, between 10% and 20%)
            // Note: summarize uses gt/lt (strict comparison), so we need to start > dayStart
            repeat(85) {
                TestServer.funnel.info.table().insertOne(
                    FunnelInstance(
                        funnel = testFunnel,
                        userAgent = "Test",
                        version = "1.0",
                        started = dayStart + (it + 1).minutes,  // Start at 1 minute after dayStart
                        expiry = dayStart + (it + 21).minutes,
                        success = dayStart + (it + 6).minutes,
                        errors = emptySet(),
                        expectedErrorRate = expectedErrorRate
                    )
                )
            }
            repeat(15) {
                TestServer.funnel.info.table().insertOne(
                    FunnelInstance(
                        funnel = testFunnel,
                        userAgent = "Test",
                        version = "1.0",
                        started = dayStart + (86 + it).minutes,
                        expiry = dayStart + (106 + it).minutes,
                        success = null,
                        errors = setOf("error"),
                        expectedErrorRate = expectedErrorRate
                    )
                )
            }

            TestServer.funnel.summarizeNow.test(null, targetDate)

            val summaries =
                TestServer.funnel.summaryInfo.table().find(condition<FunnelSummary> { it.date.eq(targetDate) }).toList()
            assertEquals(1, summaries.size)

            val summary = summaries.first()
            // Error rate 15% is between 10% (half of 20%) and 20%, so should be WARNING
            assertEquals(HealthStatus.Level.WARNING, summary.status)
        }
    }

    @Test
    fun summarize_calculates_health_status_error() = runBlocking {
        TestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            TestServer.funnel.info.table().deleteManyIgnoringOld(Condition.Always)
            TestServer.funnel.summaryInfo.table().deleteManyIgnoringOld(Condition.Always)

            val targetDate = LocalDate(2024, 3, 3)
            val zone = TimeZone.of("America/Denver")
            val dayStart = ZonedDateTime(LocalDateTime(targetDate, LocalTime(8, 0, 0)), zone).toInstant()

            val testFunnel = "health-error-funnel"
            val expectedErrorRate = 0.1f  // 10% expected

            // Insert 70 successful instances, 30 errors (30% actual error rate, above 10%)
            // Note: summarize uses gt/lt (strict comparison), so we need to start > dayStart
            repeat(70) {
                TestServer.funnel.info.table().insertOne(
                    FunnelInstance(
                        funnel = testFunnel,
                        userAgent = "Test",
                        version = "1.0",
                        started = dayStart + (it + 1).minutes,  // Start at 1 minute after dayStart
                        expiry = dayStart + (it + 21).minutes,
                        success = dayStart + (it + 6).minutes,
                        errors = emptySet(),
                        expectedErrorRate = expectedErrorRate
                    )
                )
            }
            repeat(30) {
                TestServer.funnel.info.table().insertOne(
                    FunnelInstance(
                        funnel = testFunnel,
                        userAgent = "Test",
                        version = "1.0",
                        started = dayStart + (71 + it).minutes,
                        expiry = dayStart + (91 + it).minutes,
                        success = null,
                        errors = setOf("error"),
                        expectedErrorRate = expectedErrorRate
                    )
                )
            }

            TestServer.funnel.summarizeNow.test(null, targetDate)

            val summaries =
                TestServer.funnel.summaryInfo.table().find(condition<FunnelSummary> { it.date.eq(targetDate) }).toList()
            assertEquals(1, summaries.size)

            val summary = summaries.first()
            // Error rate 30% is above 10% expected, so should be ERROR
            assertEquals(HealthStatus.Level.ERROR, summary.status)
        }
    }

    @Test
    fun summarize_replaces_existing_summary() = runBlocking {
        TestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            TestServer.funnel.info.table().deleteManyIgnoringOld(Condition.Always)
            TestServer.funnel.summaryInfo.table().deleteManyIgnoringOld(Condition.Always)

            val targetDate = LocalDate(2024, 4, 1)
            val zone = TimeZone.of("America/Denver")
            val dayStart = ZonedDateTime(LocalDateTime(targetDate, LocalTime(8, 0, 0)), zone).toInstant()

            // Insert an existing summary
            TestServer.funnel.summaryInfo.table().insertOne(
                FunnelSummary(
                    funnel = "replace-test",
                    date = targetDate,
                    status = HealthStatus.Level.ERROR,
                    success = 0.1f,
                    count = 10
                )
            )

            // Insert new funnel instances
            // Note: summarize uses gt/lt (strict comparison), so we need to start > dayStart
            repeat(5) {
                TestServer.funnel.info.table().insertOne(
                    FunnelInstance(
                        funnel = "replace-test",
                        userAgent = "Test",
                        version = "1.0",
                        started = dayStart + (it + 1).minutes,  // Start at 1 minute after dayStart
                        expiry = dayStart + (it + 21).minutes,
                        success = dayStart + (it + 6).minutes,
                        errors = emptySet()
                    )
                )
            }

            // Run summarize - should replace the existing summary
            TestServer.funnel.summarizeNow.test(null, targetDate)

            val summaries =
                TestServer.funnel.summaryInfo.table().find(condition<FunnelSummary> { it.date.eq(targetDate) }).toList()
            assertEquals(1, summaries.size)

            val summary = summaries.first()
            assertEquals(5, summary.count)
            assertEquals(1.0f, summary.success)  // All 5 instances were successful
        }
    }

    @Test
    fun funnel_workflow_complete() = runBlocking {
        TestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            // Simulate a complete funnel workflow
            val funnelStart = FunnelStart(
                funnel = "checkout-flow",
                userAgent = "Browser/1.0",
                version = "2.0.0"
            )

            // Start the funnel
            val id = TestServer.funnel.start.test(null, funnelStart)

            // Progress through steps
            TestServer.funnel.step.test(id, null, 1)  // Cart review
            TestServer.funnel.step.test(id, null, 2)  // Enter shipping
            TestServer.funnel.step.test(id, null, 3)  // Enter payment

            // Complete successfully
            TestServer.funnel.success.test(id, null, Unit)

            // Verify final state
            val instance = TestServer.funnel.info.table().get(id)
            assertNotNull(instance)
            assertEquals("checkout-flow", instance.funnel)
            assertEquals(3, instance.step)
            assertNotNull(instance.success)
            assertTrue(instance.errors.isEmpty())
        }
    }

    @Test
    fun funnel_workflow_with_recovery() = runBlocking {
        TestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            val funnelStart = FunnelStart(
                funnel = "payment-flow",
                userAgent = "Mobile/1.0",
                version = "3.0.0"
            )

            // Start the funnel
            val id = TestServer.funnel.start.test(null, funnelStart)

            // Progress with an error
            TestServer.funnel.step.test(id, null, 1)
            TestServer.funnel.error.test(id, null, "Card declined")
            TestServer.funnel.step.test(id, null, 1)  // Retry step
            TestServer.funnel.success.test(id, null, Unit)

            // Verify state shows success after error
            val instance = TestServer.funnel.info.table().get(id)
            assertNotNull(instance)
            assertEquals(1, instance.errors.size)
            assertNotNull(instance.success)
        }
    }
}
