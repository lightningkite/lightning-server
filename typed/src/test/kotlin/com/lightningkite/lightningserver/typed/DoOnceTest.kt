// by Claude
package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.services.database.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for the doOnce utility function and ActionHasOccurred.
 *
 * The doOnce function ensures an action is executed only once, using a database
 * table to track execution state. It supports:
 * - Idempotent execution (run once, skip subsequent calls)
 * - Error recovery (retry after failure)
 * - Timeout-based lock acquisition
 */
class DoOnceTest {
    private val actionTable = DatabaseTableDefinition<ActionHasOccurred>()


    object TestServer : ServerBuilder() {
        val database = setting("database", Database.Settings())
    }

    // ========== ActionHasOccurred Data Class Tests ==========

    @Test
    fun `ActionHasOccurred can be created with just id`() {
        val action = ActionHasOccurred(_id = "test-action")

        assertEquals("test-action", action._id)
        assertNull(action.started)
        assertNull(action.completed)
        assertNull(action.errorMessage)
    }

    @Test
    fun `ActionHasOccurred can be created with all fields`() = runBlocking {
        TestServer.test({}) {
            val now = com.lightningkite.lightningserver.runtime.now()
            val action = ActionHasOccurred(
                _id = "test-action",
                started = now,
                completed = now,
                errorMessage = "test error"
            )

            assertEquals("test-action", action._id)
            assertEquals(now, action.started)
            assertEquals(now, action.completed)
            assertEquals("test error", action.errorMessage)
        }
    }

    @Test
    fun `ActionHasOccurred copy works correctly`() {
        val action = ActionHasOccurred(_id = "test-action")
        val copied = action.copy(errorMessage = "new error")

        assertEquals("test-action", copied._id)
        assertEquals("new error", copied.errorMessage)
    }

    // ========== doOnce Basic Execution Tests ==========

    @Test
    fun `doOnce executes action on first call`() = runBlocking {
        TestServer.test({}) {
            var executed = false

            doOnce(
                key = "first-call-test",
                database = database
            ) {
                executed = true
            }

            assertTrue(executed, "Action should be executed on first call")
        }
    }

    @Test
    fun `doOnce does not execute action on second call`() = runBlocking {
        TestServer.test({}) {
            var executionCount = 0

            // First call
            doOnce(
                key = "second-call-test",
                database = database
            ) {
                executionCount++
            }

            // Second call
            doOnce(
                key = "second-call-test",
                database = database
            ) {
                executionCount++
            }

            assertEquals(1, executionCount, "Action should only execute once")
        }
    }

    @Test
    fun `doOnce creates ActionHasOccurred record in database`() = runBlocking {
        TestServer.test({}) {
            doOnce(
                key = "record-test",
                database = database
            ) {
                // Empty action
            }

            val table = database().table(actionTable)
            val record = table.get("record-test")

            assertNotNull(record, "Record should be created in database")
            assertEquals("record-test", record._id)
            assertNotNull(record.completed, "completed timestamp should be set")
        }
    }

    @Test
    fun `doOnce marks action as completed on success`() = runBlocking {
        TestServer.test({}) {
            doOnce(
                key = "completion-test",
                database = database
            ) {
                // Successful action
            }

            val table = database().table(actionTable)
            val record = table.get("completion-test")

            assertNotNull(record?.completed, "completed should be set on success")
            assertNull(record.errorMessage, "errorMessage should be null on success")
        }
    }

    // ========== doOnce Error Handling Tests ==========

    @Test
    fun `doOnce stores error message when action throws`() = runBlocking {
        TestServer.test({}) {
            try {
                doOnce(
                    key = "error-test",
                    database = database
                ) {
                    throw Exception("Test error message")
                }
            } catch (e: Exception) {
                // Expected
            }

            val table = database().table(actionTable)
            val record = table.get("error-test")

            assertNotNull(record, "Record should exist after error")
            assertEquals("Test error message", record.errorMessage)
            assertNull(record.started, "started should be cleared after error")
            assertNull(record.completed, "completed should not be set on error")
        }
    }

    @Test
    fun `doOnce allows retry after error`() = runBlocking {
        TestServer.test({}) {
            var executionCount = 0

            // First call - throws error
            try {
                doOnce(
                    key = "retry-test",
                    database = database
                ) {
                    executionCount++
                    throw Exception("First attempt error")
                }
            } catch (e: Exception) {
                // Expected
            }

            // Second call - should retry since first failed
            doOnce(
                key = "retry-test",
                database = database
            ) {
                executionCount++
            }

            assertEquals(2, executionCount, "Action should execute twice - once for failure, once for retry")
        }
    }

    // ========== doOnce Different Keys Tests ==========

    @Test
    fun `doOnce with different keys executes independently`() = runBlocking {
        TestServer.test({}) {
            var count1 = 0
            var count2 = 0

            doOnce(key = "independent-key-1", database = database) { count1++ }
            doOnce(key = "independent-key-2", database = database) { count2++ }

            // Second calls
            doOnce(key = "independent-key-1", database = database) { count1++ }
            doOnce(key = "independent-key-2", database = database) { count2++ }

            assertEquals(1, count1, "Key 1 should only execute once")
            assertEquals(1, count2, "Key 2 should only execute once")
        }
    }

    // ========== doOnce Timeout Tests ==========

    @Test
    fun `doOnce uses default timeout`() = runBlocking {
        TestServer.test({}) {
            // This test just verifies doOnce accepts default timeout
            doOnce(
                key = "default-timeout-test",
                database = database
            ) {
                // Action completes
            }

            val table = database().table(actionTable)
            val record = table.get("default-timeout-test")
            assertNotNull(record?.completed)
        }
    }

    @Test
    fun `doOnce accepts custom timeout`() = runBlocking {
        TestServer.test({}) {
            doOnce(
                key = "custom-timeout-test",
                database = database,
                timeout = 120.seconds
            ) {
                // Action completes
            }

            val table = database().table(actionTable)
            val record = table.get("custom-timeout-test")
            assertNotNull(record?.completed)
        }
    }
}
