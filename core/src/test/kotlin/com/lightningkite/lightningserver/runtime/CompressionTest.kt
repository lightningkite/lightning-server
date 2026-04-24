package com.lightningkite.lightningserver.runtime

import kotlin.test.Test
import kotlin.test.assertContentEquals

class CompressionTest {

    @Test
    fun testGzipCompression() {
        val original = "Hello, World! This is a test string.".toByteArray()
        val compressed = original.gzip()
        val decompressed = compressed.ungzip()

        assertContentEquals(original, decompressed, "Decompressed data should match original")
    }

    @Test
    fun testGzipLargeData() {
        val original = "x".repeat(10000).toByteArray()
        val compressed = original.gzip()
        val decompressed = compressed.ungzip()

        assertContentEquals(original, decompressed, "Decompressed large data should match original")
        assert(compressed.size < original.size) { "Compressed data should be smaller than original for repetitive data" }
    }

    @Test
    fun testGzipEmptyData() {
        val original = ByteArray(0)
        val compressed = original.gzip()
        val decompressed = compressed.ungzip()

        assertContentEquals(original, decompressed, "Empty data should compress and decompress correctly")
    }
}
