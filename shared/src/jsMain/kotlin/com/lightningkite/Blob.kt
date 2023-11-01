package com.lightningkite

import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.w3c.files.Blob as NativeBlob
import org.w3c.files.BlobPropertyBag
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise

actual class Blob(val native: NativeBlob) {
    actual val type: MimeType get() = MimeType(native.type)
    actual val size: Long? get() = native.size.toLong()
}
actual fun Blob(text: String, type: MimeType): Blob = Blob(NativeBlob(arrayOf(text), BlobPropertyBag(type = type.string)))
actual fun Blob(bytes: ByteArray, type: MimeType): Blob = Blob(NativeBlob(arrayOf(Int8Array(bytes.toTypedArray())), BlobPropertyBag(type = type.string)))
actual suspend fun Blob.text(): String = (native.asDynamic().text() as Promise<String>).await()
actual suspend fun Blob.bytes(): ByteArray = (native.asDynamic().arrayBuffer() as Promise<ArrayBuffer>).await().let { Int8Array(it) }.let {
    ByteArray(it.length) { index -> it.get(index) }
}

suspend fun <T> Promise<T>.await(): T = suspendCoroutine { cont ->
    then(
        onFulfilled = {
            cont.resume(it)
        },
        onRejected = {
            cont.resumeWithException(it)
        }
    )
}