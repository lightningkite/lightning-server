package com.lightningkite.lightningserver.bytes

@OptIn(ExperimentalUnsignedTypes::class)
fun ByteArray.toHexString(): String = asUByteArray().joinToString("") { it.toString(radix = 16).padStart(2, '0') }
fun String.hexToByteArray(): ByteArray = ByteArray(length / 2) {
    substring(it * 2, it * 2 + 1).toInt(radix = 16).toByte()
}