package com.lightningkite

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream

actual class Blob(
    actual val type: MimeType,
    actual val size: Long?,
    val read: () -> InputStream,
    val write: (out: OutputStream) -> Unit = { out -> read().use { it.copyTo(out) } },
    val textOnHand: String? = null,
    val bytesOnHand: ByteArray? = null
)

actual fun Blob(text: String, type: MimeType): Blob {
    val bytes = text.toByteArray(Charsets.UTF_8)
    return Blob(type, bytes.size.toLong(), { ByteArrayInputStream(bytes) }, { it.write(bytes) }, bytesOnHand = bytes, textOnHand = text)
}
actual fun Blob(bytes: ByteArray, type: MimeType): Blob {
    return Blob(type, bytes.size.toLong(), { ByteArrayInputStream(bytes) }, { it.write(bytes) }, bytesOnHand = bytes)
}

fun Blob(file: File, type: MimeType = MimeType.fromExtension(file.extension)): Blob {
    return Blob(type, file.length(), { FileInputStream(file) })
}

actual suspend fun Blob.text(): String = textOnHand ?: read().reader().readText()
actual suspend fun Blob.bytes(): ByteArray = bytesOnHand ?: read().readBytes()
