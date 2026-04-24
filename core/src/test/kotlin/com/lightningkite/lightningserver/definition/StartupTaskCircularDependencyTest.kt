package com.lightningkite.lightningserver.definition

import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for circular dependency detection in StartupTask.
 *
 * Verifies that the validation function added to ServerDefinition initialization
 * properly detects circular dependencies using depth-first search.
 */
class StartupTaskCircularDependencyTest {

    /**
     * Helper to create a task with mutable dependencies for testing.
     * In real code, this pattern should never be used - dependencies should be immutable.
     */
    private class MutableDependencyTask(
        private val deps: MutableList<StartupTask> = mutableListOf(),
    ) : StartupTask {
        override val dependencies: Collection<StartupTask> get() = deps
        fun addDependency(task: StartupTask) = deps.add(task)

        context(server: com.lightningkite.lightningserver.runtime.ServerRuntime)
        override suspend fun execute() {
        }
    }

    @Test
    fun `should detect simple circular dependency A to B to A`() {
        val taskA = MutableDependencyTask()
        val taskB = MutableDependencyTask()

        taskA.addDependency(taskB)
        taskB.addDependency(taskA) // Creates cycle

        val exception = assertFailsWith<IllegalStateException> {
            validateStartupTaskDependencies(listOf(taskA, taskB))
        }

        assertTrue(
            exception.message?.contains("Circular dependency detected") == true,
            "Should detect circular dependency, got: ${exception.message}"
        )
    }

    @Test
    fun `should detect three-way circular dependency A to B to C to A`() {
        val taskA = MutableDependencyTask()
        val taskB = MutableDependencyTask()
        val taskC = MutableDependencyTask()

        taskA.addDependency(taskB)
        taskB.addDependency(taskC)
        taskC.addDependency(taskA) // Creates cycle

        val exception = assertFailsWith<IllegalStateException> {
            validateStartupTaskDependencies(listOf(taskA, taskB, taskC))
        }

        assertTrue(
            exception.message?.contains("Circular dependency detected") == true,
            "Should detect circular dependency, got: ${exception.message}"
        )
    }

    @Test
    fun `should detect self-dependency`() {
        val taskA = MutableDependencyTask()
        taskA.addDependency(taskA) // Task depends on itself

        val exception = assertFailsWith<IllegalStateException> {
            validateStartupTaskDependencies(listOf(taskA))
        }

        assertTrue(
            exception.message?.contains("Circular dependency detected") == true,
            "Should detect self-dependency, got: ${exception.message}"
        )
    }

    @Test
    fun `should allow valid dependency chain without cycles`() {
        val taskA = StartupTask(dependencies = listOf()) { }
        val taskB = StartupTask(dependencies = listOf(taskA)) { }
        val taskC = StartupTask(dependencies = listOf(taskB)) { }
        val taskD = StartupTask(dependencies = listOf(taskC)) { }

        // Should not throw
        validateStartupTaskDependencies(listOf(taskA, taskB, taskC, taskD))
    }

    @Test
    fun `should allow complex DAG without cycles`() {
        val taskA = StartupTask(dependencies = listOf()) { }
        val taskB = StartupTask(dependencies = listOf()) { }
        val taskC = StartupTask(dependencies = listOf(taskA, taskB)) { }
        val taskD = StartupTask(dependencies = listOf(taskA, taskB)) { }
        val taskE = StartupTask(dependencies = listOf(taskC, taskD)) { }

        // Should not throw - this is a valid DAG (diamond pattern)
        validateStartupTaskDependencies(listOf(taskA, taskB, taskC, taskD, taskE))
    }

}
