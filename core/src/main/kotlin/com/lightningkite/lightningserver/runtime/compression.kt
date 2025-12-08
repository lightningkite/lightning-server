package com.lightningkite.lightningserver.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Compresses a byte array using GZIP compression.
 *
 * This is used internally for HTTP response compression when the client supports gzip encoding.
 *
 * @return A new byte array containing the GZIP-compressed data
 */
internal fun ByteArray.gzip(): ByteArray {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use {
        it.write(this)
        it.flush()
    }
    return out.toByteArray()
}

/**
 * Decompresses a GZIP-compressed byte array.
 *
 * This is used internally for testing and request decompression.
 *
 * @return A new byte array containing the decompressed data
 * @throws java.util.zip.ZipException if the data is not in valid GZIP format
 */
internal fun ByteArray.ungzip(): ByteArray {
    return GZIPInputStream(ByteArrayInputStream(this)).use { it.readBytes() }
}
