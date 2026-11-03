package com.lightningkite.lightningserver.typed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RequestMetadataTest {
    @Test
    fun setReplacesEveryValueRegardlessOfCase() {
        val metadata = RequestMetadata(listOf("authorization" to "old", "X-Other" to "kept"))
        metadata.set("Authorization", "new")
        assertEquals(listOf("X-Other" to "kept", "Authorization" to "new"), metadata.toList())
    }

    @Test
    fun appendKeepsExistingValues() {
        val metadata = RequestMetadata()
        metadata.append("X-Tag", "a")
        metadata.append("x-tag", "b")
        assertEquals("a", metadata["X-TAG"])
        assertEquals(listOf("a", "b"), metadata.getAll("X-Tag"))
    }

    @Test
    fun removeDropsEveryValueRegardlessOfCase() {
        val metadata = RequestMetadata(listOf("X-Masquerade" to "User/1", "x-masquerade" to "User/2"))
        metadata.remove("X-MASQUERADE")
        assertFalse(metadata.has("X-Masquerade"))
    }
}
