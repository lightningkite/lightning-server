package com.lightningkite

expect class Blob {
    val type: MimeType
    val size: Long?
}
expect fun Blob(text: String, type: MimeType): Blob
expect fun Blob(bytes: ByteArray, type: MimeType): Blob
expect suspend fun Blob.text(): String
expect suspend fun Blob.bytes(): ByteArray
