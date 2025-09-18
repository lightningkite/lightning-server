package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal fun ByteArray.gzip(): ByteArray {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use {
        it.write(this)
        it.flush()
    }
    return out.toByteArray()
}
internal fun ByteArray.ungzip(): ByteArray {
    return GZIPInputStream(ByteArrayInputStream(this)).use { it.readBytes() }
}
