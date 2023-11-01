package com.lightningkite

import kotlinx.cinterop.*
import platform.Foundation.*
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy


actual class Blob(val data: NSData, actual val type: MimeType) {
    actual val size: Long? get() = data.length.toLong()
}

actual fun Blob(text: String, type: MimeType): Blob = Blob((text as NSString).dataUsingEncoding(NSUTF8StringEncoding)!!, type)
actual fun Blob(bytes: ByteArray, type: MimeType): Blob = Blob(bytes.toData(), type)
actual suspend fun Blob.text(): String = NSString.create(data, NSUTF8StringEncoding) as String
actual suspend fun Blob.bytes(): ByteArray = data.toByteArray()

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
public fun ByteArray.toData(): NSData = memScoped {
    NSData.create(bytes = allocArrayOf(this@toData),
        length = this@toData.size.toULong())
}
@OptIn(ExperimentalForeignApi::class)
public fun NSData.toByteArray(): ByteArray = ByteArray(this@toByteArray.length.toInt()).apply {
    usePinned {
        memcpy(it.addressOf(0), this@toByteArray.bytes, this@toByteArray.length)
    }
}