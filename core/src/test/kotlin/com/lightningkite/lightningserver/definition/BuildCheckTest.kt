package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class BuildCheckTest {
    @Test
    fun checksRunOnEveryBuildAfterTheirModules() {
        val ran = mutableListOf<String>()
        val child = object : ServerBuilder() {
            init { buildCheck { ran += "child" } }
        }
        val root = object : ServerBuilder() {
            init {
                buildCheck { ran += "root" }
                path.path("child") include child
            }
        }
        root.build()
        assertEquals(listOf("child", "root"), ran)
        root.build()
        assertEquals(listOf("child", "root", "child", "root"), ran)
    }

    @Test
    fun moduleChecksReceiveTheWholeServer() {
        var seen: ServerDefinition? = null
        val child = object : ServerBuilder() {
            init { buildCheck { seen = it } }
        }
        val root = object : ServerBuilder() {
            init { path.path("child") include child }
        }
        assertSame(root.build(), seen)
    }

    @Test
    fun aFailingCheckFailsTheBuild() {
        val root = object : ServerBuilder() {
            init { buildCheck { error("misconfigured") } }
        }
        assertEquals("misconfigured", assertFailsWith<IllegalStateException> { root.build() }.message)
    }

    @Test
    fun checksSeeRegistrationsMadeAfterThem() {
        val root = object : ServerBuilder() {
            val names = mutableListOf<String>()
            init { buildCheck { check("late" in names) } }
            val late = "late".also { names += it }
        }
        root.build()
    }
}
