package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.guide.samples.architecture.*
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ArchitectureTest {
    @Test
    fun `a task launched from a request records the request as parent and origin, and inherits its context`() =
        executionChainTest()

    @Test
    fun `extensions set on builders are readable on the built definition, with writable keys merged across modules`() =
        readExtensions()

    object UnversionedServer : ServerBuilder() {
        val api = path.path("api") include VersionedApi
    }

    @Test
    fun `a build check registered in a module fails the build of the whole server`() {
        assertFailsWith<IllegalStateException> { UnversionedServer.build() }
    }

    @Test
    fun `a request cache key is calculated from the request`() = requestCacheKeyTest()
}
