package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.test.TestRunner
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PreDeployTaskTest {

    @Suppress("DEPRECATION")
    private fun <S : ServerBuilder> S.runner(): TestRunner<S> =
        TestRunner(this).also { it.settings.readyUsingDefaults() }

    @Test
    fun `runs dependencies first, resolving lazy forward references`() {
        val order = mutableListOf<String>()
        // `a` depends on `b`, which is declared *after* it. The lazy dependency lambda means this
        // forward reference resolves correctly at run time rather than capturing an uninitialized null.
        val server = object : ServerBuilder() {
            val a: PreDeployTask = path.path("a") bind PreDeployTask(dependencies = { listOf(b) }) { order.add("a") }
            val b: PreDeployTask = path.path("b") bind PreDeployTask { order.add("b") }
        }
        runBlocking { server.runner().executePreDeployTasks() }
        assertContentEquals(listOf("b", "a"), order)
    }

    @Test
    fun `runs every registered task on every invocation`() {
        var count = 0
        val server = object : ServerBuilder() {
            val t = path.path("t") bind PreDeployTask { count++ }
        }
        val runner = server.runner()
        runBlocking { runner.executePreDeployTasks() }
        runBlocking { runner.executePreDeployTasks() }
        assertEquals(2, count, "Pre-deploy tasks run every invocation - the framework tracks no history")
    }

    @Test
    fun `a failing task aborts the whole phase and dependents do not run`() {
        val ran = mutableListOf<String>()
        val server = object : ServerBuilder() {
            val fail: PreDeployTask = path.path("fail") bind PreDeployTask {
                ran.add("fail")
                throw RuntimeException("boom")
            }
            val dependent: PreDeployTask = path.path("dep") bind PreDeployTask(dependencies = { listOf(fail) }) {
                ran.add("dep")
            }
        }
        val exception = assertFailsWith<RuntimeException> {
            runBlocking { server.runner().executePreDeployTasks() }
        }
        assertEquals("boom", exception.message)
        assertTrue("dep" !in ran, "Dependent of a failed task must not run")
    }

    @Test
    fun `circular pre-deploy dependencies are rejected at build`() {
        val server = object : ServerBuilder() {
            val x: PreDeployTask = path.path("x") bind PreDeployTask(dependencies = { listOf(y) }) {}
            val y: PreDeployTask = path.path("y") bind PreDeployTask(dependencies = { listOf(x) }) {}
        }
        val exception = assertFailsWith<IllegalStateException> {
            // Accessing the flattened registry triggers finalize()'s dependency validation.
            server.build().preDeployTasks
        }
        assertTrue(exception.message?.contains("Circular dependency detected") == true)
    }

    @Test
    fun `startup task failure is no longer swallowed`() {
        // Regression: previously a failing startup task with no dependents was swallowed and the
        // server started anyway. It must now fail startup.
        val server = object : ServerBuilder() {
            val boom = path.path("boom") bind StartupTask { throw RuntimeException("startup-boom") }
        }
        val exception = assertFailsWith<RuntimeException> {
            runBlocking { server.runner().executeStartupTasks() }
        }
        assertEquals("startup-boom", exception.message)
    }
}
